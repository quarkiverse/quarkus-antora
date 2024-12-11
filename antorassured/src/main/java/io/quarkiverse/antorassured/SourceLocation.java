package io.quarkiverse.antorassured;

import java.nio.file.Path;

/**
 * A line in a file
 *
 * @since 1.0.0
 */
public record SourceLocation(Path file, int lineNumber) {
    public String toString() {
        if (lineNumber >= 1) {
            return file + ":" + lineNumber;
        }
        return file.toString();
    }
}