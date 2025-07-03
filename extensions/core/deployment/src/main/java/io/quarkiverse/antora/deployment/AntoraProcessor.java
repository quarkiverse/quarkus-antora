package io.quarkiverse.antora.deployment;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.stream.Stream;

import org.jboss.logging.Logger;
import org.yaml.snakeyaml.Yaml;

import io.quarkiverse.antora.FixedConfig;
import io.quarkiverse.antora.spi.AntoraPlaybookBuildItem;
import io.quarkus.deployment.IsDevelopment;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import io.quarkus.deployment.builditem.HotDeploymentWatchedFileBuildItem;
import io.quarkus.deployment.pkg.builditem.BuildSystemTargetBuildItem;
import io.quarkus.deployment.util.FileUtil;

public class AntoraProcessor {

    private static final String FEATURE = "antora";

    private static final int INVALID_UID = -1;
    private static Logger log = Logger.getLogger(AntoraProcessor.class);

    @BuildStep
    FeatureBuildItem feature() {
        return new FeatureBuildItem(FEATURE);
    }

    @BuildStep(onlyIf = IsDevelopment.class)
    void watchResources(BuildProducer<HotDeploymentWatchedFileBuildItem> watchedFiles) {
        final Path baseDir = Path.of(".").toAbsolutePath().normalize();
        final Path playbookPath = baseDir.resolve("antora-playbook.yml");
        if (Files.isRegularFile(playbookPath)) {
            watchedFiles.produce(new HotDeploymentWatchedFileBuildItem(playbookPath.toString()));
            try {
                final Map<String, Object> playbook = new Yaml().load(Files.readString(playbookPath, StandardCharsets.UTF_8));
                handleSupplementalFiles(playbook, (ui, oldValue) -> {
                    final Path supplementedPath = baseDir.resolve(oldValue).normalize();
                    watchDir(watchedFiles, supplementedPath);
                });

            } catch (IOException e) {
                throw new RuntimeException("Could not read " + playbookPath, e);
            }
        }
        watchedFiles.produce(new HotDeploymentWatchedFileBuildItem(baseDir.resolve("antora.yml").toString()));
        final Path modulesDir = baseDir.resolve("modules");
        watchDir(watchedFiles, modulesDir);
    }

    static void watchDir(BuildProducer<HotDeploymentWatchedFileBuildItem> watchedFiles, final Path modulesDir) {
        try (Stream<Path> files = Files.walk(modulesDir)) {
            files
                    .map(Path::toString)
                    .map(HotDeploymentWatchedFileBuildItem::new)
                    .forEach(watchedFiles::produce);
        } catch (IOException e) {
            throw new RuntimeException("Could not walk " + modulesDir, e);
        }
    }

    @BuildStep
    void buildAntoraSite(
            FixedConfig fixedConfig,
            BuildSystemTargetBuildItem buildSystemTarget,
            Optional<AntoraPlaybookBuildItem> antoraPlaybook,
            BuildProducer<GeneratedWebResourceBuildItem> staticResourceProducer) {

        final Path targetDir = buildSystemTarget.getOutputDirectory();
        if (!Files.isDirectory(targetDir)) {
            try {
                Files.createDirectories(targetDir);
            } catch (IOException e) {
                throw new RuntimeException("Could not create " + targetDir);
            }
        }
        final Path baseDir = targetDir.getParent();

        final Path gitRepoRoot = gitRepoRoot(baseDir);
        final PlaybookInfo pbInfo = augmentAntoraPlaybook(gitRepoRoot, baseDir, targetDir, antoraPlaybook);
        final Path absAntoraPlaybookPath = pbInfo.playbookPath;
        final Path antoraPlaybookPath = gitRepoRoot.relativize(absAntoraPlaybookPath);

        if (Files.isDirectory(pbInfo.outDir)) {
            try {
                FileUtil.deleteDirectory(pbInfo.outDir);
            } catch (IOException e) {
                throw new RuntimeException("Could not remove " + pbInfo.outDir);
            }
        }

        buildWithContainer(fixedConfig.image(), gitRepoRoot, antoraPlaybookPath, pbInfo.npmPackages());

        try (Stream<Path> files = Files.walk(pbInfo.outDir)) {
            files.forEach(absP -> {
                final String relPath = pbInfo.outDir.relativize(absP).toString();
                if (Files.isRegularFile(absP)) {
                    final byte[] bytes;
                    if ("index.html".equals(relPath)) {
                        final String oldContent;
                        try {
                            oldContent = Files.readString(absP, StandardCharsets.UTF_8);
                        } catch (IOException e) {
                            throw new RuntimeException("Could not read " + absP, e);
                        }
                        final Path indexHtmlCopy = targetDir.resolve("classes/META-INF/resources/index.html");
                        if (!Files.exists(indexHtmlCopy)) {
                            /* Override it only if it does not exist */
                            final String newContent = oldContent
                                    //.replaceAll("([^=/\">]*/dev/index.html)", "/antora/$1")
                                    /* Do not cache the redirect page */
                                    .replace("<meta http-equiv=\"refresh\"",
                                            "<meta http-equiv=\"Cache-Control\" content=\"no-store\">\n<meta http-equiv=\"refresh\"");
                            staticResourceProducer.produce(new GeneratedWebResourceBuildItem("/index.html",
                                    newContent.getBytes(StandardCharsets.UTF_8)));
                        }
                        bytes = oldContent
                                /* Do not cache the redirect page */
                                .replace("<meta http-equiv=\"refresh\"",
                                        "<meta http-equiv=\"Cache-Control\" content=\"no-store\">\n<meta http-equiv=\"refresh\"")
                                .getBytes(StandardCharsets.UTF_8);
                    } else {
                        try {
                            bytes = Files.readAllBytes(absP);
                        } catch (IOException e) {
                            throw new RuntimeException("Could not read " + absP, e);
                        }
                    }
                    log.infof("Producing META-INF/antora/%s", relPath);
                    staticResourceProducer.produce(new GeneratedWebResourceBuildItem("/" + relPath, bytes));
                }
            });
        } catch (IOException e) {
            throw new RuntimeException("Could not walk " + pbInfo.outDir, e);
        }

    }

    private void buildWithContainer(String antoraImageName, final Path gitRepoRoot, final Path antoraPlaybookPath,
            List<String> npmPackages) {
        try {
            new NativeImageBuildRunner().build(antoraImageName, gitRepoRoot, antoraPlaybookPath, npmPackages);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            throw new RuntimeException("Failed to build native image", e);
        }
    }

    private Path gitRepoRoot(Path startDir) {
        Path gitRepoRoot = startDir.toAbsolutePath().normalize();
        while (!Files.exists(gitRepoRoot.resolve(".git"))) {
            gitRepoRoot = gitRepoRoot.getParent();
            if (gitRepoRoot == null) {
                throw new IllegalStateException(
                        "Could not find git repository root for " + startDir);
            }
        }
        return gitRepoRoot;
    }

    private static PlaybookInfo augmentAntoraPlaybook(
            Path gitRepoRoot,
            Path baseDir,
            Path targetDir,
            Optional<AntoraPlaybookBuildItem> antoraPlaybookBuildItem) {

        final Path augmentedAntoraPlaybookYml = targetDir.resolve("antora-playbook.yml");

        Map<String, Object> playbook;
        final Yaml yaml = new Yaml();
        final Path antoraYmlPath = baseDir.resolve("antora.yml");
        final Path antoraPlaybookPath = baseDir.resolve("antora-playbook.yml");

        final Map<String, Object> antoraYaml;
        try {
            antoraYaml = yaml.load(Files.readString(antoraYmlPath, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new RuntimeException("Could not read " + antoraYmlPath, e);
        }
        playbook = defaultPlaybook(antoraYaml);

        if (antoraPlaybookBuildItem.isPresent()) {
            final Map<String, Object> buildItemPlaybook = yaml.load(antoraPlaybookBuildItem.get().getAntoraPlaybookSource());
            playbook = mergeMaps(playbook, buildItemPlaybook);
        }

        if (Files.exists(antoraPlaybookPath)) {
            try {
                final Map<String, Object> localPlaybook = yaml
                        .load(Files.readString(antoraPlaybookPath, StandardCharsets.UTF_8));
                playbook = mergeMaps(playbook, localPlaybook);
            } catch (IOException e) {
                throw new RuntimeException("Could not read " + antoraPlaybookPath, e);
            }
        }

        final Map<String, List<Map<String, Object>>> content = (Map<String, List<Map<String, Object>>>) playbook
                .computeIfAbsent("content",
                        k -> new LinkedHashMap<String, Object>());
        final List<Map<String, Object>> sources = content.computeIfAbsent("sources",
                k -> new ArrayList<Map<String, Object>>());
        /* Fix the local sources */
        final Path newLocalUrl = augmentedAntoraPlaybookYml.getParent().relativize(gitRepoRoot);
        final Path newStartPath = gitRepoRoot.relativize(antoraYmlPath.getParent());
        final AtomicBoolean fixed = new AtomicBoolean(false);
        sources.stream()
                .forEach(src -> {
                    final String url = (String) src.get("url");
                    if (url.startsWith(".")) {
                        fixed.set(true);
                        src.put("url", "./" + newLocalUrl.toString());
                        src.put("start_path", newStartPath.toString());
                    }
                });

        final Map<String, Object> output = (Map<String, Object>) playbook.computeIfAbsent("output",
                k -> new LinkedHashMap<String, Object>());
        final Path outputDir = Path.of("classes/META-INF/resources/antora");
        output.put("dir", "./" + outputDir.toString());

        handleSupplementalFiles(playbook, (ui, oldValue) -> ui.put("supplemental_files", "./." + oldValue));
        final List<String> npmPackages = new ArrayList<>();
        handleExtensions(playbook, npmPackages::add);

        try (Writer out = Files.newBufferedWriter(augmentedAntoraPlaybookYml, StandardCharsets.UTF_8)) {
            yaml.dump(playbook, out);
        } catch (IOException e) {
            throw new RuntimeException("Could not write " + augmentedAntoraPlaybookYml, e);
        }

        return new PlaybookInfo(augmentedAntoraPlaybookYml, targetDir.resolve(outputDir), npmPackages);
    }

    static void handleExtensions(Map<String, Object> playbook, Consumer<String> extensionConsumer) {
        final Object asciidoc = playbook.get("asciidoc");
        if (asciidoc instanceof Map) {
            final Object extensions = ((Map<String, Object>) asciidoc).get("extensions");
            if (extensions instanceof List) {
                ((List<String>) extensions).stream().forEach(extensionConsumer);
            }
        }
        final Object antora = playbook.get("antora");
        if (antora instanceof Map) {
            final Object extensions = ((Map<String, Object>) antora).get("extensions");
            if (extensions instanceof List) {
                ((List<Object>) extensions).stream()
                        .map(node -> {
                            if (node instanceof String) {
                                return (String) node;
                            } else if (node instanceof Map) {
                                Map<String, Object> o = (Map<String, Object>) node;
                                return (String) o.get("require");
                            } else {
                                throw new IllegalStateException(
                                        "Expected a string or object, but found " + node.getClass().getName());
                            }
                        })
                        .forEach(extensionConsumer);
            }
        }
    }

    static void handleSupplementalFiles(Map<String, Object> playbook,
            BiConsumer<Map<String, Object>, String> supplementalFilesConsumer) {
        final Object ui = playbook.get("ui");
        if (ui instanceof Map) {
            final Object rawSf = ((Map<String, Object>) ui).get("supplemental_files");
            if (rawSf instanceof String) {
                // If it is a string, then it is a directory path that we possibly need to relocate
                // https://docs.antora.org/antora/latest/playbook/ui-supplemental-files/
                //final String sf = (String) rawSf;
                final String sf = (String) rawSf;
                if (sf.startsWith("./")) {
                    supplementalFilesConsumer.accept((Map<String, Object>) ui, sf);
                }
            }
        }
    }

    private static Map<String, Object> defaultPlaybook(Map<String, Object> antoraYaml) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("site", Map.of(
                "title", antoraYaml.get("title"),
                "start_page", antoraYaml.get("name") + ":ROOT:index.adoc"));

        result.put("content", mutableMap(
                "sources", List.of(
                        mutableMap("url", "./..",
                                "branches", "HEAD",
                                "start_path", "docs"))));
        result.put("ui", Map.of(
                "bundle", Map.of(
                        "url", "https://github.com/quarkiverse/antora-ui-quarkiverse/releases/latest/download/ui-bundle.zip",
                        "snapshot", "true")));
        result.put("asciidoc", Map.of(
                "attributes", Map.of(
                        "kroki-fetch-diagram", "true")));

        return result;
    }

    static Map<String, Object> mutableMap(Object... entries) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < entries.length;) {
            result.put((String) entries[i++], entries[i++]);
        }
        return result;
    }

    static Map<String, Object> mergeMaps(Map<String, Object> base, Map<String, Object> overlay) {
        Map<String, Object> result = new LinkedHashMap<>(base);
        for (Map.Entry<String, Object> entry : overlay.entrySet()) {
            String key = entry.getKey();
            Object overlayValue = entry.getValue();
            Object baseValue = base.get(key);

            if (baseValue instanceof Map && overlayValue instanceof Map) {
                Map<String, Object> resultVal = mergeMaps((Map<String, Object>) baseValue, (Map<String, Object>) overlayValue);
                result.put(key, resultVal);
            } else {
                result.put(key, overlayValue); // overlay replaces or adds
            }
        }
        return result;
    }

    private record PlaybookInfo(Path playbookPath, Path outDir, List<String> npmPackages) {

    }
}
