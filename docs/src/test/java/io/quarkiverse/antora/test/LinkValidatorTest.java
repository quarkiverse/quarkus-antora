package io.quarkiverse.antora.test;

import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
public class LinkValidatorTest {

    @Test
    public void invalidExternalLinks() {
        final LinkValidator validator = new LinkValidator();

        final Map<String, ValidationError> testErrors = new TreeMap<>();
        final String errors = AntoraTestUtils.invalidExternalLinks(validator)
                .filter(err -> !"http://quarkus.io/training".equals(err.uri())) // known issue
                // https://github.com/quarkiverse/antora-ui-quarkiverse/issues/86
                .filter(err -> {
                    /* Put our testing errors aside */
                    if (err.paths().iterator().next().toString().endsWith("/test-page.html")) {
                        testErrors.put(err.uri(), err);
                        return false;
                    }
                    return true;
                })
                .map(ValidationError::toString)
                .collect(Collectors.joining("\n\n"));

        /* Now check if expected errors are present */
        Assertions.assertThat(testErrors).hasSize(3);

        ValidationError err = testErrors.get("https://quarkus.io/fake-page");
        Assertions.assertThat(err.uri()).isEqualTo("https://quarkus.io/fake-page");
        Assertions.assertThat(err.message()).isEqualTo("404");

        err = testErrors.get("https://quarkus.io/guides/building-native-image#fake-fragment");
        Assertions.assertThat(err.uri()).isEqualTo("https://quarkus.io/guides/building-native-image#fake-fragment");
        Assertions.assertThat(err.message())
                .isEqualTo("Could not find #fake-fragment");

        err = testErrors.get("https://salkjasjhashgajhhsahgahjas.com");
        Assertions.assertThat(err.uri()).isEqualTo("https://salkjasjhashgajhhsahgahjas.com");
        Assertions.assertThat(err.message()).isEqualTo("Unknown host salkjasjhashgajhhsahgahjas.com");

        validator.validate("https://quarkus.io/guides/building-native-image#prerequisites").assertValid();

        validator.validate(
                "http://docs.oasis-open.org/ws-sx/ws-securitypolicy/v1.2/errata01/os/ws-securitypolicy-1.2-errata01-os-complete.html#_Toc325572744")
                .assertValid();

        validator.validate(
                "https://github.com/quarkiverse/quarkus-cxf/blob/3.15/integration-tests/ws-rm-client/src/test/java/io/quarkiverse/cxf/it/ws/rm/client/WsReliableMessagingTest.java#L28")
                .assertValid();

        if (!errors.isEmpty()) {
            Assertions.fail("Link validation errors:\n\n" + errors);
        }

    }

    @Test
    void sourceMapperAutodetect() {
        final SourceMapper sourceMapper = SourceMapper.autodetect();

        final Path testHtml = sourceMapper.getSiteBranchDir().resolve("test-page.html");
        Assertions.assertThat(testHtml).isRegularFile();

        final SourceLocation loc = sourceMapper.findSource("https://quarkus.io/version/3.15/guides/getting-started", testHtml);
        Assertions.assertThat(loc.lineNumber()).isEqualTo(21);
        Assertions.assertThat(loc.file()).asString().endsWith("test-page.adoc");

    }
}
