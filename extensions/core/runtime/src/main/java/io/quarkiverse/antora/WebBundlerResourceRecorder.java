package io.quarkiverse.antora;

import java.util.Set;

import io.quarkus.runtime.RuntimeValue;
import io.quarkus.runtime.annotations.Recorder;
import io.quarkus.vertx.http.runtime.VertxHttpBuildTimeConfig;
import io.quarkus.vertx.http.runtime.VertxHttpConfig;
import io.vertx.core.Handler;
import io.vertx.ext.web.RoutingContext;

/**
 * Adapted from
 * https://github.com/quarkiverse/quarkus-web-bundler/blob/main/runtime/src/main/java/io/quarkiverse/web/bundler/runtime/WebBundlerResourceRecorder.java
 */
@Recorder
public class WebBundlerResourceRecorder {

    private final RuntimeValue<VertxHttpConfig> httpConfiguration;
    private VertxHttpBuildTimeConfig httpBuildTimeConfig;

    public WebBundlerResourceRecorder(RuntimeValue<VertxHttpConfig> httpConfiguration,
            VertxHttpBuildTimeConfig httpBuildTimeConfig) {
        this.httpConfiguration = httpConfiguration;
        this.httpBuildTimeConfig = httpBuildTimeConfig;
    }

    public Handler<RoutingContext> createHandler(final String directory,
            final Set<String> webResources, boolean devMode) {

        final Set<String> compressMediaTypes;
        if (httpBuildTimeConfig.enableCompression() && httpBuildTimeConfig.compressMediaTypes().isPresent()) {
            compressMediaTypes = Set.copyOf(httpBuildTimeConfig.compressMediaTypes().get());
        } else {
            compressMediaTypes = Set.of();
        }

        final var handlerConfig = new WebBundlerHandlerConfig(httpConfiguration.getValue().staticResources().indexPage(),
                devMode,
                compressMediaTypes);
        return new WebBundlerResourceHandler(handlerConfig, directory,
                webResources);
    }
}
