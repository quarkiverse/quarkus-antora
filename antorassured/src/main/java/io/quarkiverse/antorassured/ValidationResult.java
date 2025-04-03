package io.quarkiverse.antorassured;

import java.nio.file.Path;

/**
 * A result of a {@link Link} validation.
 *
 * @since 1.0.0
 */
public final class ValidationResult {

    private static final int NO_RETRY = -1;
    private final Link uri;
    private final String message;
    private final long retryAtSystemTimeMs;
    private final int attempt;

    /**
     * @param link that was validated
     * @return a new valid {@link ValidationResult}
     */
    public static ValidationResult valid(Link link) {
        return new ValidationResult(link, null, NO_RETRY, NO_RETRY);
    }

    /**
     * @param link that was validated
     * @param message the error message describing the validation failure
     * @return a new invalid {@link ValidationResult} without {@link #retryAtSystemTimeMs} set
     */
    public static ValidationResult invalid(Link link, String message) {
        return new ValidationResult(link, message, NO_RETRY, NO_RETRY);
    }

    /**
     * @param link that was validated
     * @param message the error message describing the validation failure
     * @param retryAtSystemTimeMs a system time in milliseconds when a new attempt to get the resource may be performed
     * @return a new invalid {@link ValidationResult} with {@link #retryAtSystemTimeMs} set
     */
    public static ValidationResult retry(Link link, String message, long retryAtSystemTimeMs, int attempt) {
        return new ValidationResult(link, message, retryAtSystemTimeMs, attempt);
    }

    ValidationResult(Link uri, String message, long retryAtSystemTimeMs, int attempt) {
        this.uri = uri;
        this.message = message;
        this.retryAtSystemTimeMs = retryAtSystemTimeMs;
        this.attempt = attempt;
    }

    public Link uri() {
        return uri;
    }

    public String message() {
        return message;
    }

    /**
     * @return {@code true} if the validated {@link Link} is valid, {@code false} otherwise
     *
     * @since 1.0.0
     */
    public boolean isValid() {
        return message == null;
    }

    /**
     * @return {@code true} if the validated {@link Link} is invalid, {@code false} otherwise
     *
     * @since 1.0.0
     */
    public boolean isInvalid() {
        return message != null;
    }

    /**
     * @throws IllegalStateException if this is an invalid result
     *
     * @since 1.0.0
     */
    public void assertValid() {
        if (message != null) {
            throw new IllegalStateException(message);
        }
    }

    /** {@inheritDoc} */
    public String toString() {
        StringBuilder result = new StringBuilder();
        uri.toShortString(result);
        if (message == null) {
            result.append(": valid");
        } else {
            result.append(": ");
            if (shouldRetry()) {
                result.append("(retry at " + retryAtSystemTimeMs + ", attempted " + attempt + " times) ");
            }
            result
                    .append(message);
            uri.occurrences().stream().map(Path::toString).forEach(p -> result.append("\n        - ").append(p));
        }
        return result.toString();
    }

    boolean shouldRetry() {
        return retryAtSystemTimeMs != NO_RETRY;
    }

    long retryAtSystemTimeMs() {
        return retryAtSystemTimeMs;
    }

    int attempt() {
        return attempt;
    }
}
