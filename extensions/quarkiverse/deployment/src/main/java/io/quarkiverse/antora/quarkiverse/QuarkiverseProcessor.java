package io.quarkiverse.antora.quarkiverse;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import io.quarkiverse.antora.spi.AntoraPlaybookBuildItem;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.util.IoUtil;

/**
 * Adapted from
 * https://github.com/quarkiverse/quarkus-web-bundler/blob/298c2f07e5346b4011332b9fe6ed15ede12e9d1b/deployment/src/main/java/io/quarkiverse/web/bundler/deployment/web/GeneratedWebResourcesProcessor.java
 */
public class QuarkiverseProcessor {

    @BuildStep
    public AntoraPlaybookBuildItem antoraPlaybookBuildItem() {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("quarkiverse-antora-playbook.yaml")) {
            final String source = new String(IoUtil.readBytes(in), StandardCharsets.UTF_8);
            return new AntoraPlaybookBuildItem(source);
        } catch (IOException e) {
            throw new RuntimeException("Could not read classpath resource quarkiverse-antora-playbook.yaml", e);
        }
    }
}
