package io.quarkiverse.antorassured;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

import io.quarkiverse.antorassured.RateLimit.RequestsPerTimeRateLimit;

public class RateLimitTest {

    @Test
    void requestsPerTimeInterval() {
        final MutableClock clock = new MutableClock(123L);
        final long resetIntervalMillis = 30_000L;
        final RateLimit limit = new RequestsPerTimeRateLimit(3, resetIntervalMillis, clock);

        final String uri = "foo";

        Assertions.assertThat(limit.scheduleInMilliseconds(uri)).isEqualTo(0L);
        Assertions.assertThat(limit.scheduleInMilliseconds(uri)).isEqualTo(0L);
        Assertions.assertThat(limit.scheduleInMilliseconds(uri)).isEqualTo(0L);

        Assertions.assertThat(limit.scheduleInMilliseconds(uri)).isEqualTo(resetIntervalMillis);

        clock.forward(resetIntervalMillis);

        Assertions.assertThat(limit.scheduleInMilliseconds(uri)).isEqualTo(0L);
        Assertions.assertThat(limit.scheduleInMilliseconds(uri)).isEqualTo(0L);
        Assertions.assertThat(limit.scheduleInMilliseconds(uri)).isEqualTo(0L);

        Assertions.assertThat(limit.scheduleInMilliseconds(uri)).isEqualTo(resetIntervalMillis);

        clock.forward(resetIntervalMillis);

        Assertions.assertThat(limit.scheduleInMilliseconds(uri)).isEqualTo(0L);

    }

    class MutableClock extends Clock {
        long millis;

        public MutableClock(long millis) {
            this.millis = millis;
        }

        @Override
        public Instant instant() {
            throw new UnsupportedOperationException();
        }

        void forward(long millis) {
            this.millis += millis;
        }

        @Override
        public long millis() {
            return millis;
        }

        @Override
        public ZoneId getZone() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Clock withZone(ZoneId zoneId) {
            throw new UnsupportedOperationException();
        }
    }

}
