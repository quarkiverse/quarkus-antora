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
import java.util.Map.Entry;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.jboss.logging.Logger;
import org.junit.jupiter.api.Assertions;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.output.BaseConsumer;
import org.testcontainers.containers.output.OutputFrame;
import org.testcontainers.containers.output.OutputFrame.OutputType;
import org.testcontainers.containers.output.WaitingConsumer;
import org.yaml.snakeyaml.Yaml;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

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
        watchedFiles.produce(new HotDeploymentWatchedFileBuildItem(baseDir.resolve("antora-playbook.yml").toString()));
        watchedFiles.produce(new HotDeploymentWatchedFileBuildItem(baseDir.resolve("antora.yml").toString()));
        final Path modulesDir = baseDir.resolve("modules");
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
            BuildSystemTargetBuildItem buildSystemTarget,
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

        /*
         * We need the current user's uid so that Antora container generates the files as that user
         * so that they can be later deleted with mvn clean
         */
        int uid = INVALID_UID;
        try {
            uid = (Integer) Files.getAttribute(targetDir, "unix:uid");
            log.info("Detected unix:uid " + uid);
        } catch (Exception e) {
            if (System.getProperty("os.name").toLowerCase().indexOf("win") < 0) {
                /* Warn on non-Windows, ignore otherwise */
                log.warn("Could not read unix:uid of " + targetDir + " directory", e);
            }
        }
        final int finalUid = uid;

        WaitingConsumer logConsumer = new WaitingConsumer().withRemoveAnsiCodes(true);

        AntoraFrameConsumer antoraFrameConsumer = new AntoraFrameConsumer();

        final Path gitRepoRoot = gitRepoRoot(baseDir);
        final PlaybookInfo pbInfo = augmentAntoraPlaybook(gitRepoRoot, baseDir, targetDir);
        final Path absAntoraPlaybookPath = pbInfo.playbookPath;
        final Path antoraPlaybookPath = gitRepoRoot.relativize(absAntoraPlaybookPath);

        if (Files.isDirectory(pbInfo.outDir)) {
            final Path movedOutDir = targetDir
                    .resolve("antora-site-" + UUID.randomUUID().toString());
            try {
                Files.move(pbInfo.outDir, movedOutDir);
            } catch (IOException e) {
                throw new RuntimeException("Could not move " + pbInfo.outDir + " -> " + movedOutDir);
            }
            try {
                FileUtil.deleteDirectory(movedOutDir);
            } catch (IOException e) {
                throw new RuntimeException("Could not remove " + movedOutDir);
            }
        }

        try (GenericContainer<?> antoraContainer = new GenericContainer<>("antora/antora:3.0.1")) {
            if (finalUid >= 0) {
                antoraContainer
                        .withCreateContainerCmdModifier(cmd -> {
                            cmd.withUser(String.valueOf(finalUid));
                        });
            }
            antoraContainer
                    .withFileSystemBind(gitRepoRoot.toString(), "/antora", BindMode.READ_WRITE)
                    .withCommand("--cache-dir=./antora-cache", antoraPlaybookPath.toString())
                    .withLogConsumer(antoraFrameConsumer)
                    .withLogConsumer(logConsumer);
            antoraContainer.start();
            try {
                logConsumer.waitUntilEnd(30, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                throw new RuntimeException("Timeout waiting for Antora log consumer", e);
            }
        }
        antoraFrameConsumer.assertNoErrors();

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
            Path targetDir) {

        final Path augmentedAntoraPlaybookYml = targetDir.resolve("antora-playbook.yml");

        final Map<String, Object> playbook;
        final Yaml yaml = new Yaml();
        final Path antoraYmlPath = baseDir.resolve("antora.yml");
        final Path antoraPlaybookPath = baseDir.resolve("antora-playbook.yml");
        if (!Files.exists(antoraPlaybookPath)) {
            final Map<String, Object> antoraYaml;
            try {
                antoraYaml = yaml.load(Files.readString(antoraYmlPath, StandardCharsets.UTF_8));
            } catch (IOException e) {
                throw new RuntimeException("Could not read " + antoraYmlPath, e);
            }
            playbook = defaultPlaybook(antoraYaml);
        } else {
            try {
                playbook = yaml.load(Files.readString(antoraPlaybookPath, StandardCharsets.UTF_8));
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
        sources.stream()
                .forEach(src -> {
                    final String url = (String) src.get("url");
                    if (url.startsWith(".")) {
                        src.put("url", "./" + newLocalUrl.toString());
                        src.put("start_path", newStartPath.toString());
                    }
                });

        final Map<String, Object> output = (Map<String, Object>) playbook.computeIfAbsent("output",
                k -> new LinkedHashMap<String, Object>());
        final Path outputDir = Path.of("classes/META-INF/resources/antora");
        output.put("dir", "./" + outputDir.toString());

        try (Writer out = Files.newBufferedWriter(augmentedAntoraPlaybookYml, StandardCharsets.UTF_8)) {
            yaml.dump(playbook, out);
        } catch (IOException e) {
            throw new RuntimeException("Could not write " + augmentedAntoraPlaybookYml, e);
        }

        return new PlaybookInfo(augmentedAntoraPlaybookYml, targetDir.resolve(outputDir));
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

    private record PlaybookInfo(Path playbookPath, Path outDir) {

    }

    static class AntoraFrame {
        String level;
        long time;
        String name;
        AntoraFile file;
        AntoraSource source;
        String msg;
        String hint;
        List<AntoraStackFrame> stack;

        public String getLevel() {
            return level;
        }

        public long getTime() {
            return time;
        }

        public String getName() {
            return name;
        }

        public AntoraFile getFile() {
            return file;
        }

        public AntoraSource getSource() {
            return source;
        }

        public String getMsg() {
            return msg;
        }

        public List<AntoraStackFrame> getStack() {
            return stack;
        }

        public String getHint() {
            return hint;
        }

        @Override
        public String toString() {
            return file + ": " + msg
                    + (hint != null ? (" " + hint) : "")
                    + (stack != null && !stack.isEmpty()
                            ? ("\n    at " + stack.stream()
                                    .map(AntoraStackFrame::toString)
                                    .collect(Collectors.joining("\n    at ")))
                            : "");
        }

        static class AntoraSource {
            String url;
            String worktree;
            String refname;
            String startPath;

            public String getUrl() {
                return url;
            }

            public String getWorktree() {
                return worktree;
            }

            public String getRefname() {
                return refname;
            }

            public String getStartPath() {
                return startPath;
            }
        }

        static class AntoraFile {
            String path;
            int line;

            @Override
            public String toString() {
                return path + ":" + line;
            }

            public String getPath() {
                return path;
            }

            public int getLine() {
                return line;
            }
        }

        static class AntoraStackFrame {
            AntoraFile file;
            AntoraSource source;

            public AntoraFile getFile() {
                return file;
            }

            public AntoraSource getSource() {
                return source;
            }

            @Override
            public String toString() {
                return file != null ? file.toString() : "";
            }
        }
    }

    private static class AntoraFrameConsumer extends BaseConsumer<AntoraFrameConsumer> {

        private final ObjectMapper mapper;
        private final List<AntoraFrame> frames = new ArrayList<>();
        private final Map<String, JsonProcessingException> exceptions = new LinkedHashMap<>();

        public AntoraFrameConsumer() {
            mapper = new ObjectMapper();
        }

        @Override
        public void accept(OutputFrame t) {
            if (t.getType() == OutputType.END) {
                return;
            }
            final String rawFrame = t.getUtf8String();
            try {
                final AntoraFrame frame = mapper.readValue(rawFrame, AntoraFrame.class);
                synchronized (frames) {
                    frames.add(frame);
                }
                switch (frame.getLevel()) {
                    case "info":
                        log.info(frame.toString());
                        break;
                    case "warn":
                        log.warn(frame.toString());
                        break;
                    case "error":
                        log.error(frame.toString());
                        break;
                    case "fatal":
                        log.fatal(frame.toString());
                        break;
                    default:
                        throw new IllegalStateException("Unexpected AntoraFrame.level " + frame.getLevel());
                }
            } catch (JsonProcessingException e) {
                synchronized (exceptions) {
                    exceptions.put(rawFrame, e);
                }
            }
        }

        public void assertNoErrors() {
            synchronized (exceptions) {
                if (!exceptions.isEmpty()) {
                    Entry<String, JsonProcessingException> e = exceptions.entrySet().iterator().next();
                    throw new RuntimeException("Could not parse AntoraFrame " + e.getKey(), e.getValue());
                }
            }

            synchronized (frames) {
                String errors = frames.stream()
                        .filter(f -> f.getLevel().equals("error") || f.getLevel().equals("fatal"))
                        .map(AntoraFrame::toString)
                        .collect(Collectors.joining("\n"));
                if (errors != null && !errors.isEmpty()) {
                    Assertions.fail(errors);
                }
            }
        }

    }
}
