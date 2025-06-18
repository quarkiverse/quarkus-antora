package io.quarkiverse.antora.spi;

import io.quarkus.builder.item.SimpleBuildItem;

/**
 * Provides a custom {@code antora-playbook.yaml} source document that will be used when generating the Antora site
 * intead of auto-generating a default {@code antora-playbook.yaml}.
 * The {@code antora-playbook.yaml} file present in the root directory of a docs Maven module will getoverlayed on top
 * of the {@code antora-playbook.yaml} provided via {@link AntoraPlaybookBuildItem}.
 *
 * @since 2.1.0
 */
public final class AntoraPlaybookBuildItem extends SimpleBuildItem {
    private final String antoraPlaybookSource;

    public AntoraPlaybookBuildItem(String antoraPlaybookSource) {
        super();
        this.antoraPlaybookSource = antoraPlaybookSource;
    }

    /**
     * @return the source of the {@code antora-playbook.yaml} document.
     */
    public String getAntoraPlaybookSource() {
        return antoraPlaybookSource;
    }
}
