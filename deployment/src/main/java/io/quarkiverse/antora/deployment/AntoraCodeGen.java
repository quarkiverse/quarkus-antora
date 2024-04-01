package io.quarkiverse.antora.deployment;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.jboss.logging.Logger;

import io.quarkus.bootstrap.prebuild.CodeGenException;
import io.quarkus.deployment.CodeGenContext;
import io.quarkus.deployment.CodeGenProvider;

/**
 */
public class AntoraCodeGen implements CodeGenProvider {
    private static final Logger log = Logger.getLogger(AntoraCodeGen.class);

    @Override
    public String providerId() {
        return "antora-target-classes-creator";
    }

    @Override
    public String inputExtension() {
        return "unneeded";
    }

    @Override
    public String inputDirectory() {
        return "unneeded";
    }

    @Override
    public boolean trigger(CodeGenContext context) throws CodeGenException {
        final Path outDir = context.outDir();
        final Path classesDir = outDir.getParent().getParent().resolve("classes");
        if (!Files.isDirectory(classesDir)) {
            try {
                Files.createDirectories(classesDir);
                log.info("Generated empty classes directory to make quarkus:dev happy");
            } catch (IOException e) {
                throw new CodeGenException("Could not create " + classesDir, e);
            }
            return true;
        }
        return false;
    }

}
