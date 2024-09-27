package io.quarkiverse.antora.test;

import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;

public record ValidationError(String uri, String message, Set<Path> paths) {
    public String toString() {
        return uri + "\n    -> " + message + "\n        - "
                + paths.stream().map(Path::toString).collect(Collectors.joining("\n        - "));
    }
}