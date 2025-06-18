package io.quarkiverse.antora.test;

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
                .get("/quarkus-antora-plain/dev/index.html")
                .then()
                .statusCode(200)
                .body(CoreMatchers.containsString("<h1 class=\"page\">Quarkus Antora</h1>"));
    }

}
