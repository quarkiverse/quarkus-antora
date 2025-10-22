package io.quarkiverse.antora;

import java.util.Optional;

import io.quarkus.runtime.annotations.ConfigPhase;
import io.quarkus.runtime.annotations.ConfigRoot;
import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

/**
 * Quarkus Antora build time configuration options that are also available at runtime but only in read-only mode.
 */
@ConfigMapping(prefix = "quarkus.antora")
@ConfigRoot(phase = ConfigPhase.BUILD_AND_RUN_TIME_FIXED)
public interface FixedConfig {

    /**
     * The fully qualified name of the Antora container image to use for generating the documentation site.
     * Example: `docker.io/antora/antora:3.0.1`
     *
     * @asciidoclet
     * @since 0.0.5
     */
    @WithDefault("docker.io/antora/antora:3.1.10")
    String image();

    /**
     * The name of the container network to start Antora container with.
     * The value `host` may come in handy when using local kroki container
     * that would otherwise be invisible for the Antora container.
     *
     * @asciidoclet
     * @since 3.27.0
     */
    Optional<String> containerNetwork();

}
