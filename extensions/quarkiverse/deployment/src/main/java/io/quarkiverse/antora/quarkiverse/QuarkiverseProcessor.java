package io.quarkiverse.antora.quarkiverse;

import io.quarkiverse.antora.spi.AntoraPlaybookBuildItem;
import io.quarkus.deployment.annotations.BuildStep;

/**
 * Adapted from
 * https://github.com/quarkiverse/quarkus-web-bundler/blob/298c2f07e5346b4011332b9fe6ed15ede12e9d1b/deployment/src/main/java/io/quarkiverse/web/bundler/deployment/web/GeneratedWebResourcesProcessor.java
 */
public class QuarkiverseProcessor {

    @BuildStep
    public AntoraPlaybookBuildItem antoraPlaybookBuildItem() {
        return new AntoraPlaybookBuildItem(
                /*
                 * This is a copy of https://github.com/quarkiverse/quarkiverse-docs/blob/main/antora-playbook.yml
                 * without sources
                 */
                """
                        site:
                          title: Quarkiverse Documentation
                          url: https://docs.quarkiverse.io
                        ui:
                          bundle:
                            url: https://github.com/quarkiverse/antora-ui-quarkiverse/releases/latest/download/ui-bundle.zip
                            snapshot: true
                        runtime:
                          fetch: true
                        asciidoc:
                          attributes:
                            page-pagination: '@'
                            kroki-fetch-diagram: true
                          extensions:
                            - asciidoctor-kroki
                                        """);
    }
}
