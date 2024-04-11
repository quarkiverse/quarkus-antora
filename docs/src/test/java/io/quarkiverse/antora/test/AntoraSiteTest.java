package io.quarkiverse.antora.test;

import java.io.IOException;

import org.hamcrest.CoreMatchers;
import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;

@QuarkusTest
public class AntoraSiteTest {

    @Test
    public void get() throws InterruptedException, IOException {
        RestAssured
                .given()
                .contentType(ContentType.HTML)
                .get("/quarkus-antora/dev/index.html")
                .then()
                .statusCode(200)
                .body(CoreMatchers.containsString("<h1 class=\"page\">Quarkus Antora</h1>"));
    }
}
