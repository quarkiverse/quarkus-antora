package io.quarkiverse.antora.deployment;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

import io.quarkiverse.antora.deployment.NativeImageBuildRunner.AntoraFrameConsumer;

public class AntoraFrameTest {

    @Test
    void parse() {
        AntoraFrameConsumer antoraFrameConsumer = new AntoraFrameConsumer();
        antoraFrameConsumer
                .accept(
                        """
                                {
                                  "level": "warn",
                                  "time": 1760616740324,
                                  "name": "asciidoctor",
                                  "file": {
                                    "path": "/antora/dev-mode-test/modules/ROOT/pages/index.adoc"
                                  },
                                  "source": {
                                    "url": "https://github.com/quarkiverse/quarkus-antora",
                                    "local": "/antora/.git",
                                    "worktree": "/antora",
                                    "refname": "HEAD",
                                    "reftype": "branch",
                                    "startPath": "dev-mode-test"
                                  },
                                  "msg": {
                                    "err": {
                                      "package": "asciidoctor-kroki",
                                      "message": "Skipping plantuml block.",
                                      "stack": "Error: Skipping plantuml block.\\n    at wrapError (/antora/node_modules/asciidoctor-kroki/src/asciidoctor-kroki.js:48:22)\\n    at constructor.<anonymous> (/antora/node_modules/asciidoctor-kroki/src/asciidoctor-kroki.js:186:30)\\n    at Object.apply (/usr/local/share/.config/yarn/global/node_modules/@asciidoctor/core/dist/node/asciidoctor.js:26192:21)\\n    at constructor.$$instance_exec (/usr/local/share/.config/yarn/global/node_modules/asciidoctor-opal-runtime/src/opal.js:3846:24)\\n    at Opal.send (/usr/local/share/.config/yarn/global/node_modules/asciidoctor-opal-runtime/src/opal.js:1671:19)\\n    at Proxy.$$19 (/usr/local/share/.config/yarn/global/node_modules/@asciidoctor/core/dist/node/asciidoctor.js:19974:24)\\n    at Opal.send (/usr/local/share/.config/yarn/global/node_modules/asciidoctor-opal-runtime/src/opal.js:1671:19)\\n    at constructor.$$process (/usr/local/share/.config/yarn/global/node_modules/@asciidoctor/core/dist/node/asciidoctor.js:19978:20)\\n    at constructor.$$call (/usr/local/share/.config/yarn/global/node_modules/asciidoctor-opal-runtime/src/opal.js:17898:26)\\n    at Object.Opal.send (/usr/local/share/.config/yarn/global/node_modules/asciidoctor-opal-runtime/src/opal.js:1671:19)\\nCaused by: Error: GET https://kroki.io/plantuml/svg/eNpLzMlMTlXQtVNIyk8CABoDA90= - server returns 403 status code; response: \\n    at httpRequest (/antora/node_modules/asciidoctor-kroki/src/http/http-client.js:45:9)\\n    at Object.httpGet [as get] (/antora/node_modules/asciidoctor-kroki/src/http/http-client.js:53:10)\\n    at Object.httpGet [as get] (/antora/node_modules/asciidoctor-kroki/src/http/node-http.js:5:65)\\n    at KrokiClient.getImage (/antora/node_modules/asciidoctor-kroki/src/kroki-client.js:70:30)\\n    at Object.module.exports.save (/antora/node_modules/asciidoctor-kroki/src/fetch.js:47:78)\\n    at createImageSrc (/antora/node_modules/asciidoctor-kroki/src/asciidoctor-kroki.js:67:38)\\n    at processKroki (/antora/node_modules/asciidoctor-kroki/src/asciidoctor-kroki.js:163:25)\\n    at constructor.<anonymous> (/antora/node_modules/asciidoctor-kroki/src/asciidoctor-kroki.js:184:16)\\n    at Object.apply (/usr/local/share/.config/yarn/global/node_modules/@asciidoctor/core/dist/node/asciidoctor.js:26192:21)\\n    at constructor.$$instance_exec (/usr/local/share/.config/yarn/global/node_modules/asciidoctor-opal-runtime/src/opal.js:3846:24)"
                                    }
                                  }
                                }
                                """);
        antoraFrameConsumer.accept(
                """
                        {
                          "level": "info",
                          "time": 1760616740324,
                          "name": "asciidoctor",
                          "file": {
                            "path": "/antora/dev-mode-test/modules/ROOT/pages/index.adoc"
                          },
                          "source": {
                            "url": "https://github.com/quarkiverse/quarkus-antora",
                            "local": "/antora/.git",
                            "worktree": "/antora",
                            "refname": "HEAD",
                            "reftype": "branch",
                            "startPath": "dev-mode-test"
                          },
                          "msg": "hello"
                        }
                        """);
        antoraFrameConsumer.assertNoErrors();
        Assertions.assertThat(antoraFrameConsumer.frames).hasSize(2);
        Assertions.assertThat(antoraFrameConsumer.frames.get(0).getLevel()).isEqualTo("warn");
        Assertions.assertThat(antoraFrameConsumer.frames.get(1).getLevel()).isEqualTo("info");

    }

}
