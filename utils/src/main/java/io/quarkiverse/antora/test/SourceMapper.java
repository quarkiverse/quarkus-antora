package io.quarkiverse.antora.test;

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

import org.yaml.snakeyaml.Yaml;

import io.quarkiverse.antora.WebBundlerResourceHandler;

/**
 * Maps HTML files generated by Antora to their associated AsciiDoc source files.
 */
public class SourceMapper {

    private static final Pattern HTML_SUFFIX_PATTERN = Pattern.compile(".html$");
    private final Path pagesSourceDir;
    private final Path siteBranchDir;
    private final Map<String, String> attributes;

    /**
     * @return a new {@link SourceMapper} created from data available in the current directory
     */
    public static SourceMapper autodetect() {
        final Path baseDir = Paths.get(".").toAbsolutePath().normalize();
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

            final String module = findKey(antoraYamlSource, "name");
            final String version = findKey(antoraYamlSource, "version");

            final Map<String, String> attributes = findAttributes(antoraYamlSource);

            final Path siteBranchDir = baseDir.resolve("target/classes").resolve(WebBundlerResourceHandler.META_INF_ANTORA)
                    .resolve(module).resolve(version);
            if (!Files.isDirectory(siteBranchDir)) {
                throw new IllegalStateException(
                        "Could not autodetect the site branch directory. Tried " + siteBranchDir + " but it does not exist");
            }
            return new SourceMapper(pagesSourceDir, siteBranchDir, attributes);
        } catch (IOException e) {
            throw new RuntimeException("Could not read " + antoraYaml, e);
        }
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

    public SourceMapper(Path pagesSourceDir, Path siteBranchDir, Map<String, String> attributes) {
        super();
        this.pagesSourceDir = pagesSourceDir;
        this.siteBranchDir = siteBranchDir;
        this.attributes = attributes;
    }

    /**
     * @param uri the URI whose location in AsciiDoc should be found
     * @param absHtmlPath a HTML file generated by Antora containing the given {@code uri}
     * @return the {@link SourceLocation} of the given {@code uri} in the the AsciiDoc file from which the given
     *         {@code absHtmlPath} was generated
     */
    public SourceLocation findSource(String uri, Path absHtmlPath) {
        final Path relHtmlPath = siteBranchDir.relativize(absHtmlPath);
        final Path absAdocPath = pagesSourceDir
                .resolve(HTML_SUFFIX_PATTERN.matcher(relHtmlPath.toString()).replaceFirst(".adoc"));
        if (Files.isRegularFile(absAdocPath)) {
            return findSourceLocation(uri, absAdocPath, attributes);
        }
        /* Fall back to the line in the HTML file */
        return findSourceLocation(uri, absHtmlPath, Collections.emptyMap());
    }

    static String findKey(String antoraYaml, String key) {
        Pattern pat = Pattern.compile(key + ": *([^\n]+)");
        Matcher m = pat.matcher(antoraYaml);
        if (m.find()) {
            return m.group(1);
        }
        throw new IllegalStateException("Could not find " + pat.pattern() + " in " + antoraYaml);
    }

    static SourceLocation findSourceLocation(String uri, Path absAdocPath, Map<String, String> attributes) {
        try {
            String adocSource = Files.readString(absAdocPath, StandardCharsets.UTF_8);
            int pos = adocSource.indexOf(uri);
            if (pos < 0) {
                // there is perhaps some AsciiDoc attribute in the URI
                String uriWithAttributes = uri;
                for (Entry<String, String> attrib : attributes.entrySet()) {
                    uriWithAttributes = uriWithAttributes.replace(attrib.getValue(), "{" + attrib.getKey() + "}");
                }
                if (!uriWithAttributes.equals(uri)) {
                    pos = adocSource.indexOf(uriWithAttributes);
                }

                if (pos < 0) {
                    // lets try some smaller chunks
                    URI parsedUri = URI.create(uri);
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

    public Path getPagesSourceDir() {
        return pagesSourceDir;
    }

    public Path getSiteBranchDir() {
        return siteBranchDir;
    }

    public Map<String, String> getAttributes() {
        return attributes;
    }
}
