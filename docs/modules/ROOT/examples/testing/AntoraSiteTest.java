package io.quarkiverse.antora.test;

import java.util.Set;

//tag::getIndex[]
import org.hamcrest.CoreMatchers;
import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;

@QuarkusTest /* the @QuarkusTest annotation lets Quarkus build and start the Antora site on http://localhost:8081/ */
public class AntoraSiteTest {

    @Test
    public void getIndex() {

        /*
         * If the site was built successfully,
         * we can get the index page and check that
         * it contains some expected content
         */
        RestAssured
                .given()
                .contentType(ContentType.HTML)
                .get("/quarkus-antora/dev/index.html")
                .then()
                .statusCode(200)
                .body(CoreMatchers.containsString("<h1 class=\"page\">Quarkus Antora</h1>"));
    }
    // end::getIndex[]

    // tag::externalLinks[]
    @Test
    public void externalLinks() {

        Set<String> ignorables = Set.of(
                /* Broken links available in test-page.adoc for the sake of testing */
                "https://salkjasjhashgajhhsahgahjas.com",
                "https://quarkus.io/fake-page",
                "https://quarkus.io/guides/building-native-image#fake-fragment");

        AntoraTestUtils.assertExternalLinksValid(err -> ignorables.contains(err.uri()));
    }
    // end::externalLinks[]

}
