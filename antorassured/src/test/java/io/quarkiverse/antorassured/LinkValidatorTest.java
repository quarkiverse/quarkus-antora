package io.quarkiverse.antorassured;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

import io.quarkiverse.antorassured.LinkValidator.LinkValidatorImpl;

public class LinkValidatorTest {

    @Test
    void parseRetryAfter() {
        long now = System.currentTimeMillis();
        final Clock clock = Clock.fixed(Instant.ofEpochMilli(now), ZoneId.of("GMT"));
        Assertions.assertThat(LinkValidatorImpl.parseRetryAfter("Thu, 01 Jan 1970 00:00:00 GMT", clock)).isEqualTo(0L);
        Assertions.assertThat(LinkValidatorImpl.parseRetryAfter("20", clock)).isEqualTo(now + 20_000L);
        Assertions.assertThat(LinkValidatorImpl.parseRetryAfter("200", clock))
                .isEqualTo(now + LinkValidatorImpl.RETRY_AFTER_MAX);
        Assertions.assertThat(LinkValidatorImpl.parseRetryAfter("foo", clock))
                .isEqualTo(now + LinkValidatorImpl.RETRY_AFTER_DEFAULT);
    }

    @Test
    void linkValidator() {
        final LinkValidator validator = LinkValidator.defaultValidator();
        validator.validate(req("https://quarkus.io/guides/building-native-image#prerequisites")).assertValid();

        validator.validate(
                req(
                        "http://docs.oasis-open.org/ws-sx/ws-securitypolicy/v1.2/errata01/os/ws-securitypolicy-1.2-errata01-os-complete.html#_Toc325572744"))
                .assertValid();

        validator.validate(
                req(
                        "https://github.com/quarkiverse/quarkus-cxf/blob/3.15/integration-tests/ws-rm-client/src/test/java/io/quarkiverse/cxf/it/ws/rm/client/WsReliableMessagingTest.java#L28"))
                .assertValid();

    }

    static ValidationRequest req(String link) {
        return new ValidationRequest(Link.ofResolved(link), 2, LinkStream.createDefaultGroup());
    }
}
