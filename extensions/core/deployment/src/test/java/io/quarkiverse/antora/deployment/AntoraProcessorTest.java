package io.quarkiverse.antora.deployment;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

public class AntoraProcessorTest {

    @Test
    void handleExtensions() {

        assertExtensions(
        // @formatter:off

                """
                asciidoc:
                    extensions:
                    - asciidoc-ext-1
                    - asciidoc-ext-2
                antora:
                    extensions:
                    - require: '@antora/ext-1'
                    - require: '@antora/ext-2'
                                  """
                // @formatter:on

                ,
                "asciidoc-ext-1", "asciidoc-ext-2",
                "@antora/ext-1", "@antora/ext-2");

        assertExtensions(
        // @formatter:off
                """
                asciidoc:
                    extensions:
                    - asciidoc-ext-1
                    - asciidoc-ext-2
                antora:
                    extensions:
                    - '@antora/ext-1'
                    - '@antora/ext-2'
                                  """
                // @formatter:on

                ,
                "asciidoc-ext-1", "asciidoc-ext-2",
                "@antora/ext-1", "@antora/ext-2");

    }

    static void assertExtensions(String yaml, String... expected) {
        final List<String> found = new ArrayList<>();
        final Map<String, Object> o = new Yaml().load(yaml);
        AntoraProcessor.handleExtensions(o, found::add);
        Assertions.assertThat(found).containsExactly(expected);
    }
}
