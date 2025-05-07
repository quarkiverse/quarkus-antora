package io.quarkiverse.antorassured;

import java.nio.file.Path;
import java.util.Objects;

/**
 * A result of a {@link Link} validation.
 *
 * @since 1.0.0
 */
public final class ValidationResult {

    private static final int NO_RETRY = -1;
    private final Link uri;
    private final int statusCode;
    private final String message;
    private final long retryAtSystemTimeMs;
    private final int attemptsPerformed;
    private final int maxAttempts;

    /**
     * @param link that was validated
     * @return a new valid {@link ValidationResult}
     * @since 1.0.0
     */
    public static ValidationResult valid(Link link, int statusCode) {
        return new ValidationResult(link, statusCode, null, NO_RETRY, 1, 0);
    }

    /**
     * @param link that was validated
     * @param message the error message describing the validation failure
     * @return a new invalid {@link ValidationResult} without {@link #retryAtSystemTimeMs} set
     * @since 1.0.0
     */
    public static ValidationResult invalid(Link link, int statusCode, String message) {
        return new ValidationResult(link, statusCode, message, NO_RETRY, 0, 0);
    }

    /**
     * @param link that was validated
     * @param message the error message describing the validation failure
     * @param retryAtSystemTimeMs a system time in milliseconds when a new attempt to get the resource may be performed
     * @param attemptsPerformed the ordinary of this attempt; first attempt has {@code attemptsPerformed} {@code 0}
     * @param maxAttempts how many attempts (past + future) can performed to validate the given {@code uri}
     * @return a new invalid {@link ValidationResult} with {@link #retryAtSystemTimeMs} set
     * @since 1.0.0
     */
    public static ValidationResult retry(Link link, int statusCode, String message, long retryAtSystemTimeMs,
            int attemptsPerformed,
            int maxAttempts) {
        return new ValidationResult(link, statusCode, message, retryAtSystemTimeMs, attemptsPerformed, maxAttempts);
    }

    ValidationResult(Link uri, int statusCode, String message, long retryAtSystemTimeMs, int attemptsPerformed,
            int maxAttempts) {
        this.uri = uri;
        this.statusCode = statusCode;
        this.message = message;
        this.retryAtSystemTimeMs = retryAtSystemTimeMs;
        this.attemptsPerformed = attemptsPerformed;
        this.maxAttempts = maxAttempts;
    }

    /**
     * @return the {@link Link} associated with this {@link ValidationResult}
     * @since 1.0.0
     */
    public Link uri() {
        return uri;
    }

    /**
     * @return the HTTP status code associated with this {@link ValidationResult}
     * @since 2.0.0
     */
    public int statusCode() {
        return statusCode;
    }

    /**
     * @return the error message associated with this {@link ValidationResult} or {@code null} if this {@link ValidationResult}
     *         is valid
     * @since 1.0.0
     */
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
            result.append(": valid")
                    .append(", attempted ")
                    .append(attemptsPerformed + " times)");
        } else {
            result
                    .append(": ")
                    .append(message)
                    .append(", attempted ")
                    .append(attemptsPerformed + " times");
            if (shouldRetry()) {
                result.append(", retry in ")
                        .append((retryAtSystemTimeMs - System.currentTimeMillis()) / 1000)
                        .append(" seconds");
            }
            uri.occurrences().stream().map(Path::toString).forEach(p -> result.append("\n        - ").append(p));
        }
        return result.toString();
    }

    @Override
    public int hashCode() {
        return Objects.hash(attemptsPerformed, maxAttempts, message, retryAtSystemTimeMs, statusCode, uri);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (obj == null)
            return false;
        if (getClass() != obj.getClass())
            return false;
        ValidationResult other = (ValidationResult) obj;
        return attemptsPerformed == other.attemptsPerformed && maxAttempts == other.maxAttempts
                && Objects.equals(message, other.message) && retryAtSystemTimeMs == other.retryAtSystemTimeMs
                && statusCode == other.statusCode && Objects.equals(uri, other.uri);
    }

    boolean shouldRetry() {
        return retryAtSystemTimeMs != NO_RETRY && attemptsPerformed != NO_RETRY && attemptsPerformed < maxAttempts;
    }

    long retryAtSystemTimeMs() {
        return retryAtSystemTimeMs;
    }

    int attempt() {
        return attemptsPerformed;
    }
}
