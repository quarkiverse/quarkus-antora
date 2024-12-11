package io.quarkiverse.antorassured;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigProvider;
import org.jboss.logging.Logger;
import org.yaml.snakeyaml.Yaml;

import io.quarkiverse.antora.WebBundlerResourceHandler;

/**
 * Maps HTML files generated by Antora to their associated AsciiDoc source files.
 *
 * @since 1.0.0
 */
public interface ResourceResolver {

    /**
     * @return a new {@link ResourceResolver} created from data available in the current directory
     *
     * @since 1.0.0
     */
    public static ResourceResolver autodetect() {
        final Path baseDir = Paths.get(".").toAbsolutePath().normalize();
        final Path gitRepoRootDir = ResourceResolverImpl.gitRepoRoot(baseDir);
        final Path pagesSourceDir = baseDir.resolve("modules/ROOT/pages");
        if (!Files.isDirectory(pagesSourceDir)) {
            throw new IllegalStateException(
                    "Could not autodetect the pages source directory. Tried " + pagesSourceDir + " but it does not exist");
        }
        final Path antoraYaml = baseDir.resolve("antora.yml");
        if (!Files.isRegularFile(antoraYaml)) {
            throw new IllegalStateException(
                    "Could not autodetect the antora.yaml file. Tried " + antoraYaml + " but it does not exist");
        }
        try {
            final String antoraYamlSource = Files.readString(antoraYaml, StandardCharsets.UTF_8);

            final String module = ResourceResolverImpl.findKey(antoraYamlSource, "name");
            final String version = ResourceResolverImpl.findKey(antoraYamlSource, "version");

            final Map<String, String> attributes = ResourceResolverImpl.findAttributes(antoraYamlSource);

            final Path siteBranchDir = baseDir.resolve("target/classes").resolve(WebBundlerResourceHandler.META_INF_ANTORA)
                    .resolve(module).resolve(version);
            if (!Files.isDirectory(siteBranchDir)) {
                throw new IllegalStateException(
                        "Could not autodetect the site branch directory. Tried " + siteBranchDir + " but it does not exist");
            }
            final Config config = ConfigProvider.getConfig();
            final String baseUri = "http://"
                    + config.getOptionalValue("quarkus.http.test-host", String.class).orElse("localhost") + ":"
                    + config.getValue("quarkus.http.test-port", String.class)
                    + "/" + module + "/" + version;
            return new ResourceResolverImpl(pagesSourceDir, siteBranchDir, attributes, baseUri, gitRepoRootDir);
        } catch (IOException e) {
            throw new RuntimeException("Could not read " + antoraYaml, e);
        }
    }

    /**
     * @param uri the URI to test
     * @return {@code true} if the given {@code uri} starts with {@code http:} or {@code https:}; otherwise {@code false}
     *
     * @since 1.0.0
     */
    public static boolean isHttpOrHttps(String uri) {
        return uri != null && (uri.startsWith("http:") || uri.startsWith("https:"));
    }

    /**
     * @param uri the URI to test
     * @return {@code true} if the given {@code uri} starts with {@code file:///antora} and ends with {@code .adoc}; otherwise
     *         {@code false}
     *
     * @since 1.0.0
     */
    public static boolean isAsciiDocSource(Link uri) {
        final String originalUri = uri.originalUri();
        return originalUri.equals(ResourceResolverImpl.FILE_ANTORA_PREFIX)
                || originalUri.startsWith(ResourceResolverImpl.FILE_ANTORA_PREFIX_WITH_SLASH) && originalUri.endsWith(".adoc");
    }

    /**
     * @param link the {@link Link} whose location in AsciiDoc should be found
     * @param absHtmlPath an absolute path to the HTML file generated by Antora containing the given {@code link}
     * @return the {@link SourceLocation} of the given {@code link} in the the AsciiDoc file from which the given
     *         {@code absHtmlPath} was generated
     *
     * @since 1.0.0
     */
    SourceLocation findSource(Link link, Path absHtmlPath);

    /**
     * Resolves the given (possibly relative {@code uri}) using the given {@code file} as base
     *
     * @param file an absolute path to the HTML document in which the given link occurs
     * @param uri a possibly relative URI to resolve
     * @return a new {@link Link} with empty {@link Link#occurrences()}
     *
     * @since 1.0.0
     */
    Link resolveUri(Path file, String uri);

    /**
     * Resolves the given {@link Path} against the branch directory of the generated Antora site.
     *
     * @param relativeToBranchDirectory an relative path to resolve
     * @return an absolute {@link Path}
     *
     * @since 1.0.0
     */
    Path resolveLocal(Path relativeToBranchDirectory);

    static class ResourceResolverImpl implements ResourceResolver {
        private static final Logger log = Logger.getLogger(ResourceResolver.class);
        private static final String FILE_ANTORA_PREFIX = "file:///antora";
        private static final String FILE_ANTORA_PREFIX_WITH_SLASH = "file:///antora/";
        private static final Pattern HTML_SUFFIX_PATTERN = Pattern.compile(".html$");
        private final Path pagesSourceDir;
        private final Path siteBranchDir;
        private final Map<String, String> attributes;
        private final String baseUri;
        private final Path gitRepoRootDir;

        public ResourceResolverImpl(Path pagesSourceDir, Path siteBranchDir, Map<String, String> attributes, String baseUri,
                Path gitRepoRootDir) {
            super();
            this.pagesSourceDir = pagesSourceDir;
            this.siteBranchDir = siteBranchDir;
            this.attributes = attributes;
            this.baseUri = baseUri;
            this.gitRepoRootDir = gitRepoRootDir;
        }

        @Override
        public SourceLocation findSource(Link link, Path absHtmlPath) {
            final Path relHtmlPath = siteBranchDir.relativize(absHtmlPath);
            final Path absAdocPath = pagesSourceDir
                    .resolve(HTML_SUFFIX_PATTERN.matcher(relHtmlPath.toString()).replaceFirst(".adoc"));
            if (Files.isRegularFile(absAdocPath)) {
                return findSourceLocation(link, absAdocPath, attributes);
            }
            /* Fall back to the line in the HTML file */
            return findSourceLocation(link, absHtmlPath, Collections.emptyMap());
        }

        @Override
        public Link resolveUri(Path file, String linkHref) {
            if (isHttpOrHttps(linkHref)) {
                return new Link(linkHref, linkHref, Collections.emptySet());
            }
            final String result;
            if (linkHref.startsWith(FILE_ANTORA_PREFIX)) {
                final String relLinkHref = linkHref.substring(FILE_ANTORA_PREFIX.length());
                result = "file://" + gitRepoRootDir.resolve(relLinkHref).toString();
            } else {

                final Path relativeToBasePath = siteBranchDir.relativize(file);
                final URI fileUri = URI.create(baseUri + "/" + relativeToBasePath);
                result = fileUri.resolve(linkHref).toString();
            }
            log.debugf("Resolved URI %s -> %s on page %s", linkHref, result, siteBranchDir.relativize(file));
            return new Link(linkHref, result, Collections.emptySet());
        }

        @Override
        public Path resolveLocal(Path relativeToBranchDirectory) {
            return siteBranchDir.resolve(relativeToBranchDirectory);
        }

        static Map<String, String> findAttributes(String antoraYamlSource) {
            Map<String, Object> yaml = new Yaml().load(antoraYamlSource);
            Object adoc = yaml.get("asciidoc");
            Object attributes;
            Map<String, String> result = new LinkedHashMap<>();
            if (adoc instanceof Map && (attributes = ((Map) adoc).get("attributes")) instanceof Map) {
                for (Entry<String, Object> en : ((Map<String, Object>) attributes).entrySet()) {
                    result.put(en.getKey(), String.valueOf(en.getValue()));
                }
            }
            return Collections.unmodifiableMap(result);
        }

        static String findKey(String antoraYaml, String key) {
            Pattern pat = Pattern.compile(key + ": *([^\n]+)");
            Matcher m = pat.matcher(antoraYaml);
            if (m.find()) {
                return m.group(1);
            }
            throw new IllegalStateException("Could not find " + pat.pattern() + " in " + antoraYaml);
        }

        static SourceLocation findSourceLocation(Link uri, Path absAdocPath, Map<String, String> attributes) {
            try {
                String adocSource = Files.readString(absAdocPath, StandardCharsets.UTF_8);
                int pos = adocSource.indexOf(uri.originalUri());
                if (pos < 0) {
                    // there is perhaps some AsciiDoc attribute in the URI
                    String uriWithAttributes = uri.resolvedUri();
                    for (Entry<String, String> attrib : attributes.entrySet()) {
                        uriWithAttributes = uriWithAttributes.replace(attrib.getValue(), "{" + attrib.getKey() + "}");
                    }
                    if (!uriWithAttributes.equals(uri.resolvedUri())) {
                        pos = adocSource.indexOf(uriWithAttributes);
                    }

                    if (pos < 0) {
                        // lets try some smaller chunks
                        URI parsedUri = URI.create(uri.resolvedUri());
                        if (parsedUri.getRawFragment() != null) {
                            pos = adocSource.indexOf(parsedUri.getRawFragment());
                        }
                        if (pos < 0 && parsedUri.getPath() != null) {
                            pos = adocSource.indexOf(parsedUri.getRawPath());
                        }
                    }
                }
                if (pos >= 0) {
                    int line = findLine(adocSource, pos);
                    return new SourceLocation(absAdocPath, line);
                }
                return new SourceLocation(absAdocPath, -1);
            } catch (IOException e) {
                throw new RuntimeException("Could not read " + absAdocPath, e);
            }
        }

        static int findLine(String adocSource, int pos) {
            int lineNumber = 1;
            int newLinePos = adocSource.indexOf('\n');
            while (newLinePos >= 0 && newLinePos < pos) {
                newLinePos = adocSource.indexOf('\n', newLinePos + 1);
                lineNumber++;
            }
            return lineNumber;
        }

        protected static Path gitRepoRoot(Path startDir) {
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

    }

}