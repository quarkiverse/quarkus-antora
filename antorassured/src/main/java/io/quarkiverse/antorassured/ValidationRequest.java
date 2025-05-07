package io.quarkiverse.antorassured;

import io.quarkiverse.antorassured.LinkStream.LinkGroup;
import io.quarkiverse.antorassured.LinkStream.LinkGroupStats;

public record ValidationRequest(Link link, int maxAttempts, LinkGroup group) {
    /**
     * Applies the continuation policies of the given {@link LinkGroup}
     *
     * @return {@code true} if the validation of the given {@link LinkGroup} should continue or {@code false} otherwise
     */
    public boolean shouldContinue() {
        final LinkGroupStats stats = group.stats();
        for (AggregatePolicy policy : group.continuationPolicies) {
            if (!policy.apply(stats).isValid()) {
                return false;
            }
        }
        return true;
    }
}
