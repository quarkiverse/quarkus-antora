package io.quarkiverse.antorassured;

import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.jboss.logging.Logger;

/**
 * A rate limit for deciding when a request to some specific URI can be scheduled.
 *
 * @since 1.4.0
 */
public interface RateLimit {

    /**
     * @param requestCountLimit how many requests can be sent during the time window defined by {@code resetIntervalMillis}
     * @param resetIntervalMillis number of milliseconds
     * @return a new {@link RateLimit} scheduling requests by number if requests per time interval per given URI
     */
    static RateLimit requestsPerTimeInterval(int requestCountLimit, long resetIntervalMillis) {
        return new RequestsPerTimeRateLimit(requestCountLimit, resetIntervalMillis);
    }

    /**
     * @param uri the URL to decide about
     * @return delay in milliseconds after which the URL can be accessed
     */
    long scheduleInMilliseconds(String uri);

    static class RequestsPerTimeRateLimit implements RateLimit {
        private static final Logger log = Logger.getLogger(AntorAssured.class);

        RequestsPerTimeRateLimit(int requestCountLimit, long resetIntervalMillis, Clock clock) {
            super();
            this.requestCountLimit = requestCountLimit;
            this.resetIntervalMillis = resetIntervalMillis;
            this.clock = clock;
        }

        RequestsPerTimeRateLimit(int requestCountLimit, long resetIntervalMillis) {
            this(requestCountLimit, resetIntervalMillis, Clock.systemUTC());
        }

        private final int requestCountLimit;
        private final long resetIntervalMillis;
        private final Clock clock;
        private final Map<String, Counter> counters = new ConcurrentHashMap<>();

        @Override
        public long scheduleInMilliseconds(String uri) {
            final Counter cnt = counters.computeIfAbsent(uri, k -> new Counter());
            synchronized (cnt) {
                if (++cnt.count <= requestCountLimit) {
                    return 0;
                }
                final long elapsedTime = clock.millis() - cnt.timestamp;
                if (elapsedTime >= resetIntervalMillis) {
                    cnt.reset();
                    return 0;
                }
                return resetIntervalMillis - elapsedTime;
            }
        }

        class Counter {
            private int count = 0;
            private long timestamp = clock.millis();

            void reset() {
                count = 1;
                timestamp = clock.millis();
            }
        }
    }

    static RateLimit none() {
        return s -> 0L;
    }
}
