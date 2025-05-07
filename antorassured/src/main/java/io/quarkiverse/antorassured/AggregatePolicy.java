package io.quarkiverse.antorassured;

import io.quarkiverse.antorassured.LinkStream.LinkGroup;
import io.quarkiverse.antorassured.LinkStream.LinkGroupStats;

/**
 * See {@link LinkStream.LinkGroup#continuationPolicy(AggregatePolicy)} and
 * {@link LinkStream.LinkGroup#finalPolicy(AggregatePolicy)}.
 *
 * @since 2.0.0
 */
public interface AggregatePolicy {

    /**
     * Apply this policy on a given {@link LinkGroupStats}
     *
     * @param stats the {@link LinkGroupStats} to check
     * @return an {@link AggregatePolicyResult}
     * @since 2.0.0
     */
    AggregatePolicyResult apply(LinkGroupStats stats);

    /**
     * @param statusCode the HTTP status code whose counts should be checked
     * @param expectedMinCount the limit
     * @return an {@link AggregatePolicy} returning a valid result if and only if the count of responses with the given
     *         {@code statusCode} is greater or equal to the given {@code expectedMinCount}
     * @since 2.0.0
     */
    static AggregatePolicy countAtLeast(int statusCode, int expectedMinCount) {
        return state -> {
            final int actualCount = state.getResponseCountByStatus(statusCode);
            return actualCount >= expectedMinCount
                    ? AggregatePolicyResult.valid()
                    : new AggregatePolicyResult("Expected at least " + expectedMinCount + " " + statusCode
                            + " responses, but found " + actualCount + " " + statusCode + " responses");
        };
    }

    /**
     * @param statusCode the HTTP status code whose counts should be checked
     * @param expectedMinCount the limit
     * @return an {@link AggregatePolicy} returning a valid result if and only if the count of responses with the given
     *         {@code statusCode} is lower or equal to the given {@code expectedMinCount}
     * @since 2.0.0
     */
    static AggregatePolicy countAtMost(int status, int expectedMaxCount) {
        return state -> {
            final int actualCount = state.getResponseCountByStatus(status);
            return actualCount <= expectedMaxCount
                    ? AggregatePolicyResult.valid()
                    : new AggregatePolicyResult("Expected at most " + expectedMaxCount + " " + status + " responses, but found "
                            + actualCount + " " + status + " responses");
        };
    }

    /**
     * The result of applying an {@link AggregatePolicy}.
     *
     * @since 2.0.0
     */
    record AggregatePolicyResult(String message) {
        private static final AggregatePolicyResult VALID = new AggregatePolicyResult(null);

        /**
         * @return a valid result
         */
        public static AggregatePolicyResult valid() {
            return VALID;
        }

        /**
         * @return {@code true} if this result is valid and {@code false} otherwise
         */
        public boolean isValid() {
            return message == null;
        }

        /**
         * @return {@code false} if this result is valid and {@code true} otherwise
         */
        public boolean isInvalid() {
            return message != null;
        }

        /**
         * @throws AssertionError if this result is invalid
         */
        public void assertValid() {
            if (message != null) {
                throw new AssertionError(message);
            }
        }
    }

}
