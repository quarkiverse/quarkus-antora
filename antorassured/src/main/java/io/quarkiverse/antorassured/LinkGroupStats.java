package io.quarkiverse.antorassured;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Statistics of a {@link LinkGroup}
 *
 * @since 2.0.0
 */
public class LinkGroupStats {
    private final Map<Integer, AtomicInteger> stats = new ConcurrentHashMap<>();

    /**
     * Records the given {@code statusCode} in this {@link LinkGroupStats}.
     *
     * @param statusCode
     *
     * @since 2.0.0
     */
    public void recordStatus(int statusCode) {
        stats.computeIfAbsent(statusCode, k -> new AtomicInteger()).incrementAndGet();
    }

    /**
     * @param statusCode the HTTP status code whose counts should returned
     * @return the number of HTTP responses having the given {@code statusCode}
     *
     * @since 2.0.0
     */
    public int getResponseCountByStatus(int statusCode) {
        return stats.computeIfAbsent(statusCode, k -> new AtomicInteger()).get();
    }
}