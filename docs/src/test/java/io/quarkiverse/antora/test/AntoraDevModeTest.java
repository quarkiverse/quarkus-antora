package io.quarkiverse.antora.test;

import static io.restassured.RestAssured.given;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.awaitility.Awaitility;
import org.hamcrest.CoreMatchers;
import org.junit.jupiter.api.Test;

import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.ValidatableResponse;

public class AntoraDevModeTest {

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
                                        .get("http://localhost:8080/antora/quarkus-antora/dev/index.html")
                                        .then();
                            } catch (Exception e) {
                                /* The reload of the service takes some time */
                                return null;
                            }
                        },
                        resp -> resp != null && resp.extract().statusCode() == 200);
                response
                        .body(CoreMatchers.containsString("<h1 class=\"page\">Quarkus Antora</h1>"));
            }

            /* Make sure new.adoc does not exist yet */
            RestAssured
                    .given()
                    .contentType(ContentType.HTML)
                    .get("http://localhost:8080/antora/quarkus-antora/dev/new.html")
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
                                            .get("http://localhost:8080/antora/quarkus-antora/dev/new.html")
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
                                            .get("http://localhost:8080/antora/quarkus-antora/dev/new.html")
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
                                            .get("http://localhost:8080/antora/quarkus-antora/dev/new.html")
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
                                    .get("http://localhost:8080/antora/quarkus-antora/dev/new.html")
                                    .then();
                        } catch (Exception e) {
                            /* The reload of the service takes some time */
                            return null;
                        }
                    },
                    resp -> resp != null && resp.extract().statusCode() == 404);

        }
    }
}
