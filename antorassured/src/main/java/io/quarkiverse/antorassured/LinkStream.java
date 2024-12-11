package io.quarkiverse.antorassured;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * A stream of {@link Link}s.
 *
 * @since 1.0.0
 */
public class LinkStream {
    private final Stream<Link> links;
    private final ResourceResolver resourceResolver;

    LinkStream(Stream<Link> links, ResourceResolver resourceResolver) {
        super();
        this.links = links;
        this.resourceResolver = resourceResolver;
    }

    /**
     * @return the underlying {@link Stream}
     *
     * @since 1.0.0
     */
    public Stream<Link> stream() {
        return links;
    }

    /**
     * @return a new {@link LinkStream} that logs the current {@link Link}
     *
     * @since 1.0.0
     */
    public LinkStream log() {
        return new LinkStream(
                links.peek(link -> AntorAssured.log.info(link)),
                resourceResolver);
    }

    /**
     * @return a new {@link LinkStream} that filters out the links satisfying
     *         {@link ResourceResolver#isAsciiDocSource(Link)}
     *
     * @since 1.0.0
     */
    public LinkStream excludeEditThisPage() {
        return exclude(ResourceResolver::isAsciiDocSource);
    }

    /**
     * @param exclude a {@link Predicate} whose matching {@link Link}s should be removed from this {@link LinkStream}
     * @return a new {@link LinkStream} that removes {@link Links} that satisfy the given {@code exclude}
     *         {@link Predicate}
     *
     * @since 1.0.0
     */
    public LinkStream exclude(Predicate<Link> exclude) {
        return new LinkStream(links.filter(link -> !exclude.test(link)), resourceResolver);
    }

    /**
     * @param exclude a {@link Pattern} applied on {@link Link#resolvedUri()}; the matching {@link Link}s will be
     *        removed from this {@link LinkStream}
     * @return a new {@link LinkStream} that removes {@link Links} that satisfy the given {@code exclude}
     *         {@link Pattern}
     *
     * @since 1.0.0
     */
    public LinkStream excludeResolved(Pattern exclude) {
        return new LinkStream(links.filter(link -> !exclude.matcher(link.resolvedUri()).matches()), resourceResolver);
    }

    /**
     * @param excludes absolute URIs to remove from this {@link LinkStream}
     * @return a new {@link LinkStream} that removes {@link Links} having with any of the given {@code excludes} as
     *         their {@link Link#resolvedUri()}
     *
     * @since 1.0.0
     */
    public LinkStream excludeResolved(String... excludes) {
        final Set<String> set = new HashSet<>();
        for (String uri : excludes) {
            set.add(uri);
        }
        return excludeResolved(set);
    }

    /**
     * @param excludes absolute URIs to remove from this {@link LinkStream}
     * @return a new {@link LinkStream} that removes {@link Links} having with any of the given {@code excludes} as
     *         their {@link Link#resolvedUri()}
     *
     * @since 1.0.0
     */
    public LinkStream excludeResolved(Collection<String> excludes) {
        return new LinkStream(links.filter(link -> !excludes.contains(link.resolvedUri())), resourceResolver);
    }

    /**
     * @param include a {@link Pattern} applied on {@link Link#resolvedUri()}; only the matching {@link Link}s will be
     *        kept in this {@link LinkStream}
     * @return a new {@link LinkStream} that keeps only {@link Links} matching the given {@code include} {@link Pattern}
     *
     * @since 1.0.0
     */
    public LinkStream includeResolved(Pattern include) {
        return new LinkStream(links.filter(link -> include.matcher(link.resolvedUri()).matches()), resourceResolver);
    }

    /**
     * @return same as {@code validate(LinkValidator.defaultValidator())}
     *
     * @since 1.0.0
     */
    public ValidationErrorStream validate() {
        return validate(LinkValidator.defaultValidator());
    }

    /**
     * @param validator the {@link LinkValidator} to use for validating {@link Link}s
     * @return a new {@link ValidationErrorStream}
     *
     * @since 1.0.0
     */
    public ValidationErrorStream validate(LinkValidator validator) {
        return new ValidationErrorStream(
                links
                        .map(validator::validate)
                        .filter(ValidationResult::isInvalid),
                resourceResolver);
    }
}
