package io.quarkiverse.antorassured;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.TreeMap;
import java.util.function.Predicate;
import java.util.stream.Stream;

import io.quarkus.arc.RemovedBean;

/**
 * A stream of invalid {@link ValidationResult}s.
 *
 * @since 1.0.0
 */
public class ValidationErrorStream {
    private final Stream<ValidationResult> errors;
    private final ResourceResolver resourceResolver;

    ValidationErrorStream(Stream<ValidationResult> errors, ResourceResolver resourceResolver) {
        super();
        this.errors = errors;
        this.resourceResolver = resourceResolver;
    }

    /**
     * @param ignorable a {@link Predicate} whose matching {@link ValidationResults}s will be {@link RemovedBean} from this
     *        {@link ValidationErrorStream}
     * @return a new {@link ValidationErrorStream} with all {@link ValidationResults}s matching the given {@code ignorable}
     *         {@link Predicate} removed
     *
     * @since 1.0.0
     */
    public ValidationErrorStream ignore(Predicate<ValidationResult> ignorable) {
        return new ValidationErrorStream(errors.filter(Predicate.not(ignorable)), resourceResolver);
    }

    /**
     * @return a new {@link ValidationErrorStream} logging the current {@link ValidationResults}
     *
     * @since 1.0.0
     */
    public ValidationErrorStream log() {
        return new ValidationErrorStream(
                errors
                        .peek(err -> AntorAssured.log.infof(
                                "Validation error: %s",
                                err)),
                resourceResolver);
    }

    /**
     * @throws AssertionError unless the underlying stream of {@link ValidationResults}s is empty
     *
     * @since 1.0.0
     */
    public void assertValid() {
        final Map<Path, List<ValidationResult>> errs = new TreeMap<>();
        errors
                .forEach(err -> {
                    for (Path p : err.uri().occurrences()) {
                        synchronized (errs) {
                            errs.computeIfAbsent(p, k -> new ArrayList<>()).add(err);
                        }
                    }
                });
        final StringBuilder sb = new StringBuilder();
        for (Entry<Path, List<ValidationResult>> en : errs.entrySet()) {
            final Path htmlPath = en.getKey();
            for (ValidationResult err : en.getValue()) {
                SourceLocation sourceLoc = resourceResolver.findSource(err.uri(), htmlPath);
                sb
                        .append("\n - ")
                        .append(sourceLoc);
                sb.append("\n     - ");
                err.uri().toShortString(sb);
                sb.append("\n         - ")
                        .append(err.message());
            }
        }
        if (!sb.isEmpty()) {
            throw new AssertionError(sb.toString());
        }
    }

    /**
     * @return the undelying {@link Stream} of {@link ValidationResult}s
     */
    public Stream<ValidationResult> stream() {
        return errors;
    }

}
