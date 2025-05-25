package io.quarkiverse.antora.test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.assertj.core.api.Assertions;
import org.awaitility.Awaitility;
import org.hamcrest.CoreMatchers;
import org.jboss.logging.Logger;
import org.junit.jupiter.api.Test;

import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.ValidatableResponse;

public class AntoraDevModeTest {

    private static final Logger log = Logger.getLogger(AntoraDevModeTest.class);

    @Test
    public void edit() throws InterruptedException, IOException {

        final Path baseDir = Path.of(".").toAbsolutePath().normalize();
        try (DevModeProcess devMode = new DevModeProcess(baseDir)) {
            {
                final ValidatableResponse response = Awaitility.await().atMost(10, TimeUnit.SECONDS).until(
                        () -> {
                            try {
                                return RestAssured
                                        .given()
                                        .contentType(ContentType.HTML)
                                        .get("http://localhost:8080/quarkus-antora/dev/index.html")
                                        .then();
                            } catch (Exception e) {
                                /* The reload of the service takes some time */
                                return null;
                            }
                        },
                        resp -> {
                            log.info("resp " + (resp != null ? resp.extract().statusCode() : ""));
                            return resp != null && resp.extract().statusCode() == 200;
                        });
                response
                        .body(CoreMatchers.containsString("<h1 class=\"page\">Lorem ipsum</h1>"));
            }

            /* Make sure the SVG file for the UML diagram was generated */
            final Path imagesDir = baseDir.resolve("target/classes/META-INF/antora/quarkus-antora/dev/_images");
            Assertions.assertThat(imagesDir).isDirectory();
            try (Stream<Path> files = Files.list(imagesDir)) {
                final Optional<String> svgFile = files
                        .map(Path::getFileName)
                        .map(Path::toString)
                        .filter(fn -> fn.startsWith("alice-and-bob-") && fn.endsWith(".svg"))
                        .findFirst();
                Assertions.assertThat(svgFile)
                        .withFailMessage(() -> "Expected " + imagesDir.toString() + "/alice-and-bob-*.svg to exist")
                        .isNotEmpty();
            }

            /* Make sure new.adoc does not exist yet */
            RestAssured
                    .given()
                    .contentType(ContentType.HTML)
                    .get("http://localhost:8080/quarkus-antora/dev/new.html")
                    .then()
                    .statusCode(404);

            /* Add new.adoc */
            Path newFile = baseDir.resolve("modules/ROOT/pages/new.adoc");
            String uniqueContent = UUID.randomUUID().toString();
            try {
                Files.writeString(newFile, "= New Page\n\n" + uniqueContent, StandardCharsets.UTF_8);
                {
                    final ValidatableResponse response = Awaitility.await().atMost(10, TimeUnit.SECONDS).until(
                            () -> {
                                try {
                                    return RestAssured
                                            .given()
                                            .contentType(ContentType.HTML)
                                            .get("http://localhost:8080/quarkus-antora/dev/new.html")
                                            .then();
                                } catch (Exception e) {
                                    /* The reload of the service takes some time */
                                    return null;
                                }
                            },
                            resp -> resp != null && resp.extract().statusCode() == 200);
                    response.body(CoreMatchers.containsString(uniqueContent));
                }

                /* Add an invalid link to new.adoc */
                Files.writeString(newFile, "= New Page\n\nxref:non-existent-page.adoc[non-existent]", StandardCharsets.UTF_8);
                {
                    final ValidatableResponse response = Awaitility.await().atMost(10, TimeUnit.SECONDS).until(
                            () -> {
                                try {
                                    return RestAssured
                                            .given()
                                            .contentType(ContentType.HTML)
                                            .get("http://localhost:8080/quarkus-antora/dev/new.html")
                                            .then();
                                } catch (Exception e) {
                                    /* The reload of the service takes some time */
                                    return null;
                                }
                            },
                            resp -> resp != null && resp.extract().statusCode() == 500);
                    response.body(CoreMatchers.containsString("target of xref not found: non-existent-page.adoc"));
                }

                /* Fix it */
                Files.writeString(newFile, "= New Page\n\n" + uniqueContent, StandardCharsets.UTF_8);
                {
                    final ValidatableResponse response = Awaitility.await().atMost(10, TimeUnit.SECONDS).until(
                            () -> {
                                try {
                                    return RestAssured
                                            .given()
                                            .contentType(ContentType.HTML)
                                            .get("http://localhost:8080/quarkus-antora/dev/new.html")
                                            .then();
                                } catch (Exception e) {
                                    /* The reload of the service takes some time */
                                    return null;
                                }
                            },
                            resp -> resp != null && resp.extract().statusCode() == 200);
                    response.body(CoreMatchers.containsString(uniqueContent));
                }

            } finally {
                Files.deleteIfExists(newFile);
            }

            /* Make sure new.html is not served anymore */
            Awaitility.await().atMost(10, TimeUnit.SECONDS).until(
                    () -> {
                        try {
                            return RestAssured
                                    .given()
                                    .contentType(ContentType.HTML)
                                    .get("http://localhost:8080/quarkus-antora/dev/new.html")
                                    .then();
                        } catch (Exception e) {
                            /* The reload of the service takes some time */
                            return null;
                        }
                    },
                    resp -> resp != null && resp.extract().statusCode() == 404);

            /* Live edit supplemental-ui */
            {
                final ValidatableResponse response = Awaitility.await().atMost(10, TimeUnit.SECONDS).until(
                        () -> {
                            try {
                                return RestAssured
                                        .given()
                                        .contentType(ContentType.HTML)
                                        .get("http://localhost:8080/quarkus-antora/dev/index.html")
                                        .then();
                            } catch (Exception e) {
                                /* The reload of the service takes some time */
                                return null;
                            }
                        },
                        resp -> resp != null && resp.extract().statusCode() == 200);
                response
                        .body(CoreMatchers.containsString(">Home supplemental<"));
            }
            final Path headerContentHbs = baseDir.resolve("supplemental-ui/partials/header-content.hbs");
            final String oldContent = Files.readString(headerContentHbs, StandardCharsets.UTF_8);
            try {
                Assertions.assertThat(oldContent).contains(">Home supplemental<");
                Files.writeString(headerContentHbs, oldContent.replace(">Home supplemental<", ">Home supplemental changed<"),
                        StandardCharsets.UTF_8);
                {
                    Awaitility.await().atMost(10, TimeUnit.SECONDS).until(
                            () -> {
                                try {
                                    return RestAssured
                                            .given()
                                            .contentType(ContentType.HTML)
                                            .get("http://localhost:8080/quarkus-antora/dev/index.html")
                                            .then();
                                } catch (Exception e) {
                                    /* The reload of the service takes some time */
                                    return null;
                                }
                            },
                            resp -> resp != null && resp.extract().statusCode() == 200
                                    && resp.extract().body().asString().contains(">Home supplemental changed<"));
                }
            } finally {
                /* Restore the old content bc. it is tracked by git */
                Files.writeString(headerContentHbs, oldContent, StandardCharsets.UTF_8);
            }
        }
    }
}
