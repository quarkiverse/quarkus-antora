package io.quarkiverse.antorassured;

import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import io.quarkiverse.antorassured.AggregatePolicy.AggregatePolicyResult;

/**
 * A group if {@link Link}s defined by a {@link Pattern} on which additional constraints and policies can be applied.
 *
 * @since 2.0.0
 */
public class LinkGroup {
    private final LinkStream parent;
    private final Pattern pattern;
    private final RateLimit rateLimit;
    final List<Function<Stream<Link>, Stream<Link>>> streamTransformers;
    final List<AggregatePolicy> continuationPolicies;
    private final List<AggregatePolicy> finalPolicies;
    private final LinkGroupStats stats;
    private final Map<String, List<String>> headers;
    final Function<Link, Link> linkMapper;
    private final FragmentValidator fragmentValidator;

    LinkGroup(
            LinkStream parent,
            Pattern pattern,
            Function<Link, Link> linkMapper,
            Map<String, List<String>> headers,
            RateLimit rateLimit,
            List<Function<Stream<Link>, Stream<Link>>> streamTransformers,
            List<AggregatePolicy> continuationPolicies,
            List<AggregatePolicy> finalPolicies,
            LinkGroupStats stats,
            FragmentValidator fragmentValidator) {
        this.parent = parent;
        this.pattern = pattern;
        this.linkMapper = linkMapper;
        this.headers = headers;
        this.rateLimit = rateLimit;
        this.streamTransformers = streamTransformers;
        this.continuationPolicies = continuationPolicies;
        this.finalPolicies = finalPolicies;
        this.stats = stats;
        this.fragmentValidator = fragmentValidator;
    }

    /**
     * @param regExp a regular expression defining the {@link LinkGroup}
     * @return a new {@link LinkGroup}
     *
     * @since 2.0.0
     */
    public LinkGroup pattern(String regExp) {
        return new LinkGroup(
                parent,
                Pattern.compile(regExp),
                linkMapper,
                headers,
                rateLimit,
                streamTransformers,
                continuationPolicies,
                finalPolicies,
                stats,
                fragmentValidator);
    }

    /**
     * @return the {@link Pattern} defining this {@link LinkGroup}
     *
     * @since 2.0.0
     */
    public Pattern pattern() {
        return pattern;
    }

    /**
     * Add the given HTTP header to all requests targeting this {@link LinkGroup}.
     *
     * @param key the HTTP header name
     * @param value the HTTP header value
     * @return a new {@link LinkGroup}
     *
     * @since 2.0.0
     */
    public LinkGroup header(String key, String value) {
        final Map<String, List<String>> newHeaders = new LinkedHashMap<>(headers);
        newHeaders.compute(key, (k, v) -> {
            if (v == null) {
                return Collections.singletonList(value);
            } else {
                v = new ArrayList<>(v);
                v.add(value);
                return Collections.unmodifiableList(v);
            }
        });
        return new LinkGroup(
                parent,
                pattern,
                linkMapper,
                newHeaders,
                rateLimit,
                streamTransformers,
                continuationPolicies,
                finalPolicies,
                stats,
                fragmentValidator);
    }

    public Map<String, List<String>> headers() {
        return headers;
    }

    /**
     * Set Basic Authorization header.
     *
     * @param username
     * @param password
     * @return a new {@link LinkGroup}
     *
     * @since 2.0.0
     */
    public LinkGroup basicAuth(String username, String password) {
        return header("Authorization",
                "Basic " + Base64.getEncoder().encodeToString((username + ":" + password).getBytes()));
    }

    /**
     * Set Bearer Authorization header using the given {@code token}.
     *
     * @param token
     * @return a new {@link LinkGroup}
     *
     * @since 2.0.0
     */
    public LinkGroup bearerToken(String token) {
        return header("Authorization", "Bearer " + token);
    }

    /**
     * Switch the original link to something else.
     * <p>
     * This is useful e.g. in case of some {@code http://github.com} links that are rate limited,
     * but once they are mapped to {@code http://api.github.com} and accessed with a {@link #bearerToken(String)}
     * then the limits are much higher and the result is equivalent.
     *
     * @param token
     * @return a new {@link LinkGroup}
     *
     * @since 2.0.0
     */
    public LinkGroup linkMapper(Function<Link, Link> linkMapper) {
        return new LinkGroup(
                parent,
                pattern,
                linkMapper,
                headers,
                rateLimit,
                streamTransformers,
                continuationPolicies,
                finalPolicies,
                stats,
                fragmentValidator);
    }

    /**
     * @param rateLimit the {@link RateLimit} to apply on this {@link LinkGroup}
     * @return a new {@link LinkGroup}
     *
     * @since 2.0.0
     */
    public LinkGroup rateLimit(RateLimit rateLimit) {
        return new LinkGroup(
                parent,
                pattern,
                linkMapper,
                headers,
                rateLimit,
                streamTransformers,
                continuationPolicies,
                finalPolicies,
                stats,
                fragmentValidator);
    }

    /**
     * @return the {@link RateLimit} set on this {@link LinkGroup} or {@code null} if none has been set
     */
    public RateLimit rateLimit() {
        return rateLimit;
    }

    /**
     * Shuffle the order of the Links belonging to this {@link LinkGroup}.
     * The default order is alphabetic by {@link Link#resolvedUri()}.
     *
     * @return a new {@link LinkGroup}
     *
     * @since 2.0.0
     */
    public LinkGroup randomOrder() {
        return new LinkGroup(
                parent,
                pattern,
                linkMapper,
                headers,
                rateLimit,
                copyAndAdd(
                        streamTransformers,
                        stream -> {
                            final List<Link> complement = new ArrayList<>();
                            final List<Link> group = new ArrayList<>();
                            stream.forEach(link -> {
                                if (pattern.matcher(link.resolvedUri()).matches()) {
                                    synchronized (group) {
                                        group.add(link);
                                    }
                                } else {
                                    synchronized (complement) {
                                        complement.add(link);
                                    }
                                }
                            });
                            Collections.shuffle(group);
                            return Stream.concat(complement.stream(), group.stream());
                        }),
                continuationPolicies,
                finalPolicies,
                stats,
                fragmentValidator);
    }

    /**
     * Apply the given {@code policy} before validating each {@link Links} of this {@link LinkGroup}.
     * <p>
     * Handy e.g. for skipping the rest of links in the {@link LinkGroup} upon encountering {@code 429 Too many requests}.
     *
     * @param policy the {@link AggregatePolicy} to apply
     * @return a new {@link LinkGroup}
     *
     * @since 2.0.0
     */
    public LinkGroup continuationPolicy(AggregatePolicy assertion) {
        return new LinkGroup(
                parent,
                pattern,
                linkMapper,
                headers,
                rateLimit,
                streamTransformers,
                copyAndAdd(continuationPolicies, assertion),
                finalPolicies,
                stats,
                fragmentValidator);
    }

    /**
     * Apply the given {@code policy} after validating all {@link Links} of the parent {@link LinkStream}.
     * <p>
     * Handy for enforcing some positive assertions, such as at least n {@link Link}s valid for the given group,
     * when some {@link #continuationPolicy(AggregatePolicy)} is set that skips a portion of links, e.g. upon
     * encountering {@code 429 Too many requests}.
     *
     * @param policy the {@link AggregatePolicy} to apply
     * @return a new {@link LinkGroup}
     *
     * @since 2.0.0
     */
    public LinkGroup finalPolicy(AggregatePolicy policy) {
        return new LinkGroup(
                parent,
                pattern,
                linkMapper,
                headers,
                rateLimit,
                streamTransformers,
                continuationPolicies,
                copyAndAdd(finalPolicies, policy),
                stats,
                fragmentValidator);
    }

    public LinkGroup fragmentValidator(FragmentValidator fragmentValidator) {
        return new LinkGroup(
                parent,
                pattern,
                linkMapper,
                headers,
                rateLimit,
                streamTransformers,
                continuationPolicies,
                finalPolicies,
                stats,
                fragmentValidator);
    }

    public FragmentValidator fragmentValidator() {
        return fragmentValidator;
    }

    /**
     * @return the {@link LinkGroupStats} associated with this {@link LinkGroup}
     */
    public LinkGroupStats stats() {
        return stats;
    }

    /**
     * Add this {@link LinkGroup} to the parent {@link LinkStream}.
     *
     * @return the parent {@link LinkStream}
     *
     * @since 2.0.0
     */
    public LinkStream endGroup() {
        if (parent == null) {
            throw new IllegalStateException("Cannot end parentless group");
        }
        final List<LinkGroup> newGroups = new ArrayList<>(parent.groups);
        newGroups.add(parent.groups.size() - 1, this);
        return new LinkStream(parent.links, parent.resourceResolver, parent.retryAttempts, newGroups,
                parent.overallTimeout);
    }

    ValidationResult applyFinalPolicies() {
        for (AggregatePolicy policy : finalPolicies) {
            AggregatePolicyResult result = policy.apply(stats);
            if (!result.isValid()) {
                return ValidationResult.invalid(Link.ofResolved(pattern.pattern()), -5, result.message(), -1);
            }
        }
        return ValidationResult.valid(Link.ofResolved(pattern.pattern()), 0);
    }

    static <T> List<T> copyAndAdd(List<T> old, T newElement) {
        final ArrayList<T> result = new ArrayList<>(old);
        result.add(newElement);
        return result;
    }

}