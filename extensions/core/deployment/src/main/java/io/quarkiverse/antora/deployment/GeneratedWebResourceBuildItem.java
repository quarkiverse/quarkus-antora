package io.quarkiverse.antora.deployment;

import static io.quarkiverse.antora.WebBundlerResourceHandler.META_INF_ANTORA;

import io.quarkus.builder.item.MultiBuildItem;
import io.quarkus.runtime.util.HashUtil;

/**
 * Adapted from
 * https://github.com/quarkiverse/quarkus-web-bundler/blob/main/deployment/src/298c2f07e5346b4011332b9fe6ed15ede12e9d1b/java/io/quarkiverse/web/bundler/deployment/web/GeneratedWebResourceBuildItem.java
 */
public final class GeneratedWebResourceBuildItem extends MultiBuildItem {

    private final String publicPath;
    private final byte[] content;
    private final String contentHash;

    public GeneratedWebResourceBuildItem(String publicPath, byte[] content) {
        this.publicPath = publicPath;
        this.content = content;
        this.contentHash = HashUtil.sha512(content);
    }

    public String resourceName() {
        return META_INF_ANTORA + publicPath;
    }

    public String publicPath() {
        return publicPath;
    }

    public byte[] content() {
        return content;
    }

    public String contentHash() {
        return contentHash;
    }
}
