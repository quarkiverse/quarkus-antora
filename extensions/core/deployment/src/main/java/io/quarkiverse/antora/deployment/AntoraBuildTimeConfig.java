package io.quarkiverse.antora.deployment;

import java.util.List;
import java.util.Optional;

import io.quarkus.runtime.annotations.ConfigPhase;
import io.quarkus.runtime.annotations.ConfigRoot;
import io.smallrye.config.ConfigMapping;

@ConfigMapping(prefix = "quarkus.antora")
@ConfigRoot(phase = ConfigPhase.BUILD_TIME)
public interface AntoraBuildTimeConfig {

    /**
     * A comma-separated list of extra command line options to add to `antora` command when building the site.
     *
     * @asciidoclet
     * @since 3.33.0
     */
    Optional<List<String>> additionalArgs();
}
