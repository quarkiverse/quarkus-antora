package io.quarkiverse.antora.test;

import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

import io.quarkiverse.antorassured.AntorAssured;
import io.quarkiverse.antorassured.Link;
import io.quarkiverse.antorassured.ResourceResolver;
import io.quarkiverse.antorassured.SourceLocation;
import io.quarkiverse.antorassured.ValidationResult;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
@QuarkusTestResource(TestRemoteServerResource.class)
public class LinkValidatorTest {

    @Test
    public void invalidExternalLinks() {

        final Map<String, ValidationResult> testErrors = new TreeMap<>();
        final String errors = AntorAssured
                .links()
                .excludeEditThisPage()
                .excludeResolved(Pattern.compile(".*/test-page.html"))
                .excludeResolved(Pattern.compile("^\\Qhttp://localhost:8080\\E.*"))
                .validate()
                .stream()
                .filter(err -> {
                    /* Put our testing errors aside */
                    if (err.uri().occurrences().iterator().next().toString().endsWith("/test-page.html")) {
                        testErrors.put(err.uri().originalUri(), err);
                        return false;
                    }
                    return true;
                })
                .map(ValidationResult::toString)
                .collect(Collectors.joining("\n\n"));

        /* Now check if expected errors are present */
        Assertions.assertThat(testErrors).hasSize(3);

        ValidationResult err = testErrors.get("https://quarkus.io/fake-page");
        Assertions.assertThat(err.uri().resolvedUri()).isEqualTo("https://quarkus.io/fake-page");
        Assertions.assertThat(err.message()).isEqualTo("404");

        err = testErrors.get("https://quarkus.io/guides/building-native-image#fake-fragment");
        Assertions.assertThat(err.uri().resolvedUri())
                .isEqualTo("https://quarkus.io/guides/building-native-image#fake-fragment");
        Assertions.assertThat(err.message())
                .isEqualTo("Could not find #fake-fragment");

        err = testErrors.get("https://salkjasjhashgajhhsahgahjas.com");
        Assertions.assertThat(err.uri().resolvedUri()).isEqualTo("https://salkjasjhashgajhhsahgahjas.com");
        Assertions.assertThat(err.message()).isEqualTo("Unable to connect");

        if (!errors.isEmpty()) {
            Assertions.fail("Link validation errors:\n\n" + errors);
        }

    }

    @Test
    void sourceMapperAutodetect() {
        final ResourceResolver sourceMapper = ResourceResolver.autodetect();

        final Path testHtml = sourceMapper.resolveLocal(Path.of("test-page.html"));
        Assertions.assertThat(testHtml).isRegularFile();

        Link link = Link.ofResolved("https://quarkus.io/version/3.15/guides/getting-started");
        final SourceLocation loc = sourceMapper.findSource(link, testHtml);
        Assertions.assertThat(loc.lineNumber()).isEqualTo(25);
        Assertions.assertThat(loc.file()).asString().endsWith("test-page.adoc");

    }
}
