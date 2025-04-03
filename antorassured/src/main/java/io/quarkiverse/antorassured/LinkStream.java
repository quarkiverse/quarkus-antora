package io.quarkiverse.antorassured;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.ListIterator;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.jboss.logging.Logger;

/**
 * A stream of {@link Link}s.
 *
 * @since 1.0.0
 */
public class LinkStream {
    private static final Logger log = Logger.getLogger(AntorAssured.class);
    private final Stream<Link> links;
    private final ResourceResolver resourceResolver;
    private final int retryAttempts;

    LinkStream(Stream<Link> links, ResourceResolver resourceResolver, int retryAttempts) {
        super();
        this.links = links;
        this.resourceResolver = resourceResolver;
        this.retryAttempts = retryAttempts;
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
                resourceResolver,
                retryAttempts);
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
        return new LinkStream(links.filter(link -> !exclude.test(link)), resourceResolver, retryAttempts);
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
        return new LinkStream(links.filter(link -> !exclude.matcher(link.resolvedUri()).matches()), resourceResolver,
                retryAttempts);
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
        return new LinkStream(links.filter(link -> !excludes.contains(link.resolvedUri())), resourceResolver, retryAttempts);
    }

    /**
     * @param include a {@link Pattern} applied on {@link Link#resolvedUri()}; only the matching {@link Link}s will be
     *        kept in this {@link LinkStream}
     * @return a new {@link LinkStream} that keeps only {@link Links} matching the given {@code include} {@link Pattern}
     *
     * @since 1.0.0
     */
    public LinkStream includeResolved(Pattern include) {
        return new LinkStream(links.filter(link -> include.matcher(link.resolvedUri()).matches()), resourceResolver,
                retryAttempts);
    }

    /**
     * @param retryAttempts how many times it should be re-tried to retrieve a remote link, in case it responds with HTTP
     *        301, 429, 500, 501, 502, 503 or 504. If not set the default is one retry attempt after 10 seconds or what
     *        the {@code Retry-After} HTTP header prescribes, but at most 120 seconds.
     * @return a new {@link LinkStream} with {@link #retryAttempts} reset to the given value
     *
     * @since 1.3.0
     */
    public LinkStream retryAttempts(int retryAttempts) {
        return new LinkStream(links, resourceResolver, retryAttempts);
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
        final List<ValidationResult> invalidWithouRetry = new ArrayList<>();
        final List<ValidationResult> invalidWithRetry = new ArrayList<>();
        links
                .map(validator::validate)
                .filter(ValidationResult::isInvalid)
                .forEach(result -> {
                    (result.shouldRetry() ? invalidWithRetry : invalidWithouRetry).add(result);
                });

        if (!invalidWithRetry.isEmpty() && retryAttempts > 0) {
            /* sort the retries by retry time */
            Collections.sort(invalidWithRetry, Comparator.comparing(ValidationResult::retryAtSystemTimeMs));

            /* Retry */
            for (int attempt = 2; attempt <= retryAttempts + 1; attempt++) {
                final ListIterator<ValidationResult> it = invalidWithRetry.listIterator();
                while (it.hasNext()) {
                    final ValidationResult oldResult = it.next();
                    final long delay = oldResult.retryAtSystemTimeMs() - System.currentTimeMillis();
                    if (delay > 0L) {
                        try {
                            log.infof("Sleeping %d ms to retry %s", delay, oldResult);
                            Thread.sleep(delay);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new RuntimeException(e);
                        }
                    }
                    final ValidationResult newResult = validator.validate(oldResult.uri(), attempt);
                    if (newResult.isValid()) {
                        it.remove();
                    } else if (newResult.shouldRetry()) {
                        it.set(newResult);
                    } else {
                        it.remove();
                        invalidWithRetry.add(newResult);
                    }
                }
            }
        }
        return new ValidationErrorStream(
                Stream.concat(invalidWithouRetry.stream(), invalidWithRetry.stream()),
                resourceResolver);
    }
}
