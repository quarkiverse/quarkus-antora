package io.quarkiverse.antora.test;

import java.nio.file.Path;

/**
 * A line in a file
 */
public record SourceLocation(Path file, int lineNumber) {
    public String toString() {
        if (lineNumber >= 1) {
            return file + ":" + lineNumber;
        }
        return file.toString();
    }
}