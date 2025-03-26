package io.quarkiverse.antorassured;

import java.nio.file.Path;
import java.util.Map;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import io.quarkiverse.antorassured.ResourceResolver.ResourceResolverImpl;

public class ResourceResolverTest {

    @Test
    void findLine() {
        Assertions.assertThat(ResourceResolverImpl.findLine("0", 0)).isEqualTo(1);
        Assertions.assertThat(ResourceResolverImpl.findLine("012", 1)).isEqualTo(1);
        Assertions.assertThat(ResourceResolverImpl.findLine("012\n456", 1)).isEqualTo(1);
        Assertions.assertThat(ResourceResolverImpl.findLine("012\n456", 4)).isEqualTo(2);
        Assertions.assertThat(ResourceResolverImpl.findLine("012\n456", 6)).isEqualTo(2);
    }

    @Test
    void resolveUri() {
        final Path pagesSourceDir = Path.of("/foo/bar/src");
        final Path siteBranchDir = Path.of("/foo/bar/site");
        final String baseUri = "http://localhost:8081";
        final ResourceResolver r = new ResourceResolverImpl(pagesSourceDir, siteBranchDir, Map.of(), baseUri,
                ResourceResolverImpl.gitRepoRoot(Path.of(".")));
        Assertions.assertThat(r.resolveUri(Path.of("/foo/bar/site/index.html"), "page.html").resolvedUri())
                .isEqualTo("http://localhost:8081/page.html");
        Assertions.assertThat(r.resolveUri(Path.of("/foo/bar/site/folder/index.html"), "../page.html").resolvedUri())
                .isEqualTo("http://localhost:8081/page.html");
    }

    @Test
    void findKeyVersion() {
        final Map<String, Object> yaml = new Yaml()
                .load("""
                        name: quarkus-cxf
                        title: CXF
                        version: '3.20'
                        nav:
                          - modules/ROOT/nav.adoc

                        asciidoc:
                          attributes:

                            # Versions
                            quarkus-version: 3.20.0 # replace ${quarkus.version}
                            quarkus-cxf-version: 3.20.0 # replace ${release.current-version}

                            # Toggle whether some page elements are rendered
                            doc-show-badges: true # Whether JVM / Native badges are rendered
                            doc-show-advanced-features: true # Whether documentation relating to advanced features is rendered
                            doc-show-user-guide-link: true # Whether the user guide link is rendered in component extensions
                            doc-show-extra-content: false # Whether additional content hosted in external AsciiDoc files should be loaded via an include:: directive
                            doc-is-main: true # Release notes and release planning are only shown and maintained in the main branch

                            quarkus-cxf-project-name: Quarkus CXF

                            # External URLs
                            link-quarkus-cxf-source-tree-base: https://github.com/quarkiverse/quarkus-cxf/tree/main
                            link-quarkus-code-generator: code.quarkus.io
                            link-camel-quarkus-docs-index: https://camel.apache.org/camel-quarkus/latest/index.html
                            link-camel-quarkus-docs-cxf-soap: https://camel.apache.org/camel-quarkus/latest/reference/extensions/cxf-soap.html
                            link-quarkus-docs-base: https://quarkus.io/guides

                            # Misc
                            javaxOrJakartaPackagePrefix: jakarta # this can be switched to javax in older branches
                                        """);

        Assertions.assertThat(ResourceResolverImpl.findKey(yaml, "name")).isEqualTo("quarkus-cxf");
        Assertions.assertThat(ResourceResolverImpl.findKey(yaml, "version")).isEqualTo("3.20");

    }

}
