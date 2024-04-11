package io.quarkiverse.antora;

import java.util.Set;

/**
 * Adapted from
 * https://github.com/quarkiverse/quarkus-web-bundler/blob/main/runtime/src/main/java/io/quarkiverse/web/bundler/runtime/WebBundlerHandlerConfig.java
 */
public class WebBundlerHandlerConfig {
    public final String indexPage;
    public final boolean devMode;
    public final Set<String> compressMediaTypes;

    public WebBundlerHandlerConfig(String indexPage, boolean devMode,
            Set<String> compressMediaTypes) {
        this.indexPage = indexPage;
        this.devMode = devMode;
        this.compressMediaTypes = compressMediaTypes;
    }

}
