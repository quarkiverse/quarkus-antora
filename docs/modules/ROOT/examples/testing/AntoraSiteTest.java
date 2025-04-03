package io.quarkiverse.antora.test;

//tag::getIndex[]
import org.hamcrest.CoreMatchers;
import org.junit.jupiter.api.Test;

import io.quarkiverse.antorassured.AntorAssured;
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
    public void validateLinks() {

        AntorAssured
                .links()
                .excludeResolved(
                        /* Broken links available in test-page.adoc for the sake of testing */
                        "https://salkjasjhashgajhhsahgahjas.com",
                        "https://quarkus.io/fake-page",
                        "https://quarkus.io/guides/building-native-image#fake-fragment",
                        "http://localhost:8083/retry-me",
                        /* When running this test, the port is 8081, so the following link is not expected to work */
                        "http://localhost:8080/quarkus-antora/dev/index.html")
                .excludeEditThisPage()
                /*
                 * Retry once (default) in case of HTTP status 301, 429, 500, 501, 502, 503
                 * or 504, after 10 seconds or what the Retry-After HTTP header prescribes,
                 * but at most 120 seconds
                 */
                .retryAttempts(1)
                .validate()
                .assertValid();
    }
    // end::externalLinks[]

}
