package io.quarkiverse.antora.deployment;

import io.quarkus.deployment.IsDevelopment;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.devui.spi.page.CardPageBuildItem;
import io.quarkus.devui.spi.page.Page;
import io.quarkus.vertx.http.deployment.NonApplicationRootPathBuildItem;

public class AntoraDevUiProcessor {

    @BuildStep(onlyIf = IsDevelopment.class)
    public CardPageBuildItem pages(NonApplicationRootPathBuildItem nonApplicationRootPathBuildItem) {

        CardPageBuildItem cardPageBuildItem = new CardPageBuildItem();

        cardPageBuildItem.addPage(Page.externalPageBuilder("Antora site")
                .url("/index.html")
                .icon("font-awesome-solid:file-lines"));

        return cardPageBuildItem;
    }
}
