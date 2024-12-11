package io.quarkiverse.antorassured;

import java.nio.file.Path;
import java.util.stream.Collectors;

/**
 * A result of a {@link Link} validation.
 *
 * @since 1.0.0
 */
public final class ValidationResult {

    private final Link uri;
    private final String message;

    /**
     * @param link that was validated
     * @return a new valid {@link ValidationResult}
     */
    public static ValidationResult valid(Link link) {
        return new ValidationResult(link, null);
    }

    /**
     * @param link that was validated
     * @param message the error message describing the validation failure
     * @return a new invalid {@link ValidationResult}
     */
    public static ValidationResult invalid(Link link, String message) {
        return new ValidationResult(link, message);
    }

    ValidationResult(Link uri, String message) {
        this.uri = uri;
        this.message = message;
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
        if (message == null) {
            return "Link " + uri.originalUri() + " -> " + uri.resolvedUri() + ": valid";
        } else {
            return "Link " + uri.originalUri() + " -> " + uri.resolvedUri() + ": " + message + "\n        - "
                    + uri.occurrences().stream().map(Path::toString).collect(Collectors.joining("\n        - "));
        }
    }
}