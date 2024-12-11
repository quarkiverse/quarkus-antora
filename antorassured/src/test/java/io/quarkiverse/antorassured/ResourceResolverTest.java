package io.quarkiverse.antorassured;

import java.nio.file.Path;
import java.util.Map;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

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

}
