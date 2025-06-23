package io.quarkiverse.antorassured;

import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.assertj.core.api.Assertions;
import org.assertj.core.api.Assumptions;
import org.assertj.core.api.ListAssert;
import org.jboss.logging.Logger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import io.restassured.RestAssured;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.BodyHandler;

public class LinkStreamTest {
    private static final Logger log = Logger.getLogger(LinkStreamTest.class);
    private static HttpServer server;

    private static final String USERNAME = "joe";
    private static final String PASSWORD = "secret1234";
    private static final String TOKEN = "deadbeef";

    @Test
    void retry() {

        Assertions.assertThat(
                links("http://localhost:8084/alternate/503/200/1")
                        .retryAttempts(1)
                        .validate()
                        .stream())
                .isEmpty();

        /* 200 should not be retried */
        Assertions.assertThat(
                links("http://localhost:8084/alternate/200/503/1")
                        .retryAttempts(1)
                        .validate()
                        .stream())
                .isEmpty();

        /* 503 returned twice should fail */
        Assertions.assertThat(
                links("http://localhost:8084/constant/503/1")
                        .retryAttempts(1)
                        .validate()
                        .stream()
                        .map(ValidationResult::toString))
                .containsExactly(
                        "http://localhost:8084/constant/503/1: 503, Retry-After: 1, attempted 2 times");
    }

    @Test
    void overallTimeout() {
        Assertions.assertThat(
                links(
                        "http://localhost:8084/sleep/200/one",
                        "http://localhost:8084/sleep/200/two")
                        .overallTimeout(100)
                        .validate()
                        .stream()
                        .map(ValidationResult::toString))
                .containsExactly(
                        "http://localhost:8084/sleep/200/two: Did not try, overall timeout of 100 ms expired, attempted 0 times");
    }

    @Test
    void rateLimit() {
        final long start = System.currentTimeMillis();
        Assertions.assertThat(
                links(
                        "http://localhost:8084/constant/200/1",
                        "http://localhost:8084/rateLimit/2/1000/one",
                        "http://localhost:8084/rateLimit/2/1000/two",
                        "http://localhost:8084/rateLimit/2/1000/three",
                        "http://localhost:8084/rateLimit/2/1000/four")
                        .group("http://localhost:8084/rateLimit/2/1000/.*")
                        .rateLimit(RateLimit.requestsPerTimeInterval(2, 1010))
                        .endGroup()
                        .validate()
                        .stream())
                .isEmpty();
        long duration = System.currentTimeMillis() - start;
        /* It should take around 1 second */
        Assertions.assertThat(duration).isGreaterThan(999);
    }

    @Test
    void randomOrder() {
        ListAssert<String> listAssert = Assertions.assertThat(
                links(
                        "http://localhost:8084/constant/401/0",
                        "http://localhost:8084/constant/401/1",
                        "http://localhost:8084/constant/401/2",
                        "http://localhost:8084/constant/401/3",
                        "http://localhost:8084/constant/401/4",
                        "http://localhost:8084/constant/401/5",
                        "http://localhost:8084/constant/401/6",
                        "http://localhost:8084/constant/401/7",
                        "http://localhost:8084/constant/401/8",
                        "http://localhost:8084/constant/401/9",
                        "http://localhost:8084/constant/404/default")
                        .group("http://localhost:8084/constant/401/.*")
                        .randomOrder()
                        .endGroup()
                        .validate()
                        .stream()
                        .map(ValidationResult::toString))
                .containsExactlyInAnyOrder(
                        "http://localhost:8084/constant/404/default: 404, attempted 1 times",
                        "http://localhost:8084/constant/401/0: 401, attempted 1 times",
                        "http://localhost:8084/constant/401/1: 401, attempted 1 times",
                        "http://localhost:8084/constant/401/2: 401, attempted 1 times",
                        "http://localhost:8084/constant/401/3: 401, attempted 1 times",
                        "http://localhost:8084/constant/401/4: 401, attempted 1 times",
                        "http://localhost:8084/constant/401/5: 401, attempted 1 times",
                        "http://localhost:8084/constant/401/6: 401, attempted 1 times",
                        "http://localhost:8084/constant/401/7: 401, attempted 1 times",
                        "http://localhost:8084/constant/401/8: 401, attempted 1 times",
                        "http://localhost:8084/constant/401/9: 401, attempted 1 times");
        listAssert.element(0).isEqualTo("http://localhost:8084/constant/404/default: 404, attempted 1 times");

        List<? extends String> actual = new ArrayList<>(listAssert.actual());
        actual.remove(0);
        boolean isRandom = false;
        for (int i = 0; i < actual.size(); i++) {
            String str = actual.get(i);
            Assertions.assertThat(str).startsWith("http://localhost:8084/constant/401/");
            if (!isRandom && !str.startsWith("http://localhost:8084/constant/401/" + i + ":")) {
                isRandom = true;
            }
        }
        Assertions.assertThat(isRandom).withFailMessage("Expected random ordering, found %s", actual).isTrue();
    }

    @Test
    void finalPolicy() {
        RestAssured.delete("http://localhost:8084/accessLog")
                .then()
                .statusCode(201);
        Assertions.assertThat(
                links(
                        "http://localhost:8084/constant/200/0",
                        "http://localhost:8084/constant/200/1",
                        "http://localhost:8084/constant/200/2",
                        "http://localhost:8084/constant/200/3",
                        "http://localhost:8084/constant/200/default")

                        .group("http://localhost:8084/constant/200/\\d+")
                        .finalPolicy(AggregatePolicy.countAtLeast(200, 4))
                        .endGroup()

                        .validate()
                        .stream())
                .isEmpty();

        List<String> accessLog = Arrays.asList(RestAssured.get("http://localhost:8084/accessLog")
                .then()
                .statusCode(200)
                .extract().body().asString().split(","));

        Assertions.assertThat(accessLog)
                .containsExactlyInAnyOrder(
                        "/constant/200/0 200",
                        "/constant/200/1 200",
                        "/constant/200/2 200",
                        "/constant/200/3 200",
                        "/constant/200/default 200");

    }

    @Test
    void finalPolicyFail() {
        RestAssured.delete("http://localhost:8084/accessLog")
                .then()
                .statusCode(201);
        Assertions.assertThat(
                links(
                        "http://localhost:8084/constant/200/0",
                        "http://localhost:8084/constant/200/1",
                        "http://localhost:8084/constant/200/2",
                        "http://localhost:8084/constant/200/default")

                        .group("http://localhost:8084/constant/200/\\d+")
                        .finalPolicy(AggregatePolicy.countAtLeast(200, 4))
                        .endGroup()

                        .validate()
                        .stream()
                        .map(ValidationResult::toString))
                .containsExactly(
                        "http://localhost:8084/constant/200/\\d+: Expected at least 4 200 responses, but found 3 200 responses, attempted 0 times");

        List<String> accessLog = Arrays.asList(RestAssured.get("http://localhost:8084/accessLog")
                .then()
                .statusCode(200)
                .extract().body().asString().split(","));

        Assertions.assertThat(accessLog)
                .containsExactlyInAnyOrder(
                        "/constant/200/0 200",
                        "/constant/200/1 200",
                        "/constant/200/2 200",
                        "/constant/200/default 200");

    }

    @Test
    void giveUpAfterFirst429() {
        RestAssured.delete("http://localhost:8084/accessLog")
                .then()
                .statusCode(201);
        /*
         * http://localhost:8084/rateLimit/4/500/0 through http://localhost:8084/rateLimit/4/500/3 should pass,
         * http://localhost:8084/rateLimit/4/500/4 should be reported as 429 and the rest should be ignored
         */
        Assertions.assertThat(
                links(
                        "http://localhost:8084/rateLimit/4/500/0",
                        "http://localhost:8084/rateLimit/4/500/1",
                        "http://localhost:8084/rateLimit/4/500/2",
                        "http://localhost:8084/rateLimit/4/500/3",
                        "http://localhost:8084/rateLimit/4/500/4",
                        "http://localhost:8084/rateLimit/4/500/5",
                        "http://localhost:8084/rateLimit/4/500/6",
                        "http://localhost:8084/constant/200/default")
                        .group("http://localhost:8084/rateLimit/4/500/.*")

                        .continuationPolicy(AggregatePolicy.countAtMost(429, 0))
                        .endGroup()
                        .validate()
                        .stream())
                .isEmpty();

        List<String> accessLog = Arrays.asList(RestAssured.get("http://localhost:8084/accessLog")
                .then()
                .statusCode(200)
                .extract().body().asString().split(","));

        Assertions.assertThat(accessLog)
                .contains("/constant/200/default 200")
                .anyMatch(s -> s.endsWith(" 429"))
                .hasSize(6);

    }

    @Test
    void basicAuth() {
        RestAssured.delete("http://localhost:8084/accessLog")
                .then()
                .statusCode(201);

        Assertions.assertThat(
                links(
                        "http://localhost:8084/basicAuth/valid",
                        "http://localhost:8084/basicAuth/anonymous",
                        "http://localhost:8084/basicAuth/invalid")

                        .group("http://localhost:8084/basicAuth/valid")
                        .basicAuth(USERNAME, PASSWORD)
                        .endGroup()

                        .group("http://localhost:8084/basicAuth/invalid")
                        .basicAuth(USERNAME, "bad")
                        .endGroup()

                        .validate()
                        .stream()
                        .map(ValidationResult::toString))
                .containsExactlyInAnyOrder(
                        "http://localhost:8084/basicAuth/anonymous: 401, attempted 1 times",
                        "http://localhost:8084/basicAuth/invalid: 401, attempted 1 times");

        List<String> accessLog = Arrays.asList(RestAssured.get("http://localhost:8084/accessLog")
                .then()
                .statusCode(200)
                .extract().body().asString().split(","));

        Assertions.assertThat(accessLog)
                .containsExactlyInAnyOrder(
                        "/basicAuth/valid 200",
                        "/basicAuth/anonymous 401",
                        "/basicAuth/invalid 401");

    }

    @Test
    void bearerToken() {
        RestAssured.delete("http://localhost:8084/accessLog")
                .then()
                .statusCode(201);

        Assertions.assertThat(
                links(
                        "http://localhost:8084/bearerToken/valid",
                        "http://localhost:8084/bearerToken/anonymous",
                        "http://localhost:8084/bearerToken/invalid")

                        .group("http://localhost:8084/bearerToken/valid")
                        .bearerToken(TOKEN)
                        .endGroup()

                        .group("http://localhost:8084/bearerToken/invalid")
                        .bearerToken("bad")
                        .endGroup()

                        .validate()
                        .stream()
                        .map(ValidationResult::toString))
                .containsExactlyInAnyOrder(
                        "http://localhost:8084/bearerToken/anonymous: 401, attempted 1 times",
                        "http://localhost:8084/bearerToken/invalid: 401, attempted 1 times");

        List<String> accessLog = Arrays.asList(RestAssured.get("http://localhost:8084/accessLog")
                .then()
                .statusCode(200)
                .extract().body().asString().split(","));

        Assertions.assertThat(accessLog)
                .containsExactlyInAnyOrder(
                        "/bearerToken/valid 200",
                        "/bearerToken/anonymous 401",
                        "/bearerToken/invalid 401");

    }

    @Test
    void fragment() {
        RestAssured.delete("http://localhost:8084/accessLog")
                .then()
                .statusCode(201);
        Assertions.assertThat(
                links(
                        "http://localhost:8084/fragment/javaDoc#parse(java.lang.CharSequence)")
                        .validate()
                        .stream())
                .isEmpty();

        List<String> accessLog = Arrays.asList(RestAssured.get("http://localhost:8084/accessLog")
                .then()
                .statusCode(200)
                .extract().body().asString().split(","));

        Assertions.assertThat(accessLog)
                .containsExactly("/fragment/javaDoc 200");

    }

    @Test
    void gitHubBlobRaw() {

        final String ghToken = System.getenv("GITHUB_TOKEN");
        if (ghToken == null) {
            log.warn("Set GITHUB_TOKEN environment variable to test GitHub links");
        } else {
            log.info("GITHUB_TOKEN set");
        }

        Assumptions.assumeThat(ghToken).isNotBlank();

        Assertions.assertThat(
                links(
                        "https://github.com/quarkiverse/quarkus-cxf/tree/3.15/integration-tests/ws-trust/src/main/resources/ws-trust-1.4-service.wsdl#L95",
                        "https://github.com/quarkiverse/quarkus-cxf/tree/3.15/integration-tests/ws-trust/src/main/resources/ws-trust-1.4-service.wsdl#L163",
                        "https://github.com/quarkiverse/quarkus-cxf/tree/3.15/integration-tests/ws-rm-client/src/test/java/io/quarkiverse/cxf/it/ws/rm/client/WsReliableMessagingTest.java#L28",
                        "https://github.com/quarkiverse/quarkus-cxf/blob/3.15/integration-tests/ws-trust/src/main/resources/application.properties#L5",
                        "https://github.com/quarkiverse/quarkus-cxf/blob/3.15/integration-tests/ws-trust/src/main/resources/application.properties#L7-L9",
                        "https://github.com/quarkiverse/quarkus-cxf/blob/3.15/integration-tests/ws-trust/src/main/resources/asymmetric-saml2-policy.xml",
                        "https://github.com/quarkiverse/quarkus-cxf/blob/3.15/integration-tests/ws-rm-client/src/test/java/io/quarkiverse/cxf/it/ws/rm/client/WsReliableMessagingTest.java#L28")

                        .group(LinkGroupFactory.gitHubRawBlobLinks(ghToken))
                        .endGroup()

                        .validate()
                        .stream())
                .isEmpty();

    }

    @Test
    void gitHubBlobHtml() {

        final String ghToken = System.getenv("GITHUB_TOKEN");
        if (ghToken == null) {
            log.warn("Set GITHUB_TOKEN environment variable to test GitHub links");
        } else {
            log.info("GITHUB_TOKEN set");
        }

        Assumptions.assumeThat(ghToken).isNotBlank();

        Assertions.assertThat(
                links(
                        "https://github.com/quarkiverse/quarkus-cxf/blob/3.15/integration-tests/ws-trust/README.adoc#regenerate-keystores")

                        .group("https://github.com/[^/]+/[^/]+/(:?blob|tree)/.*")
                        .bearerToken(ghToken)
                        .header("X-GitHub-Api-Version", "2022-11-28")
                        .header("Accept", "application/vnd.github.html+json")
                        .linkMapper(link -> {
                            final String result = Pattern
                                    .compile(
                                            "https://github.com/([^/]+)/([^/]+)/(?:blob|tree)/([^/]+)/([^\\#]*)(\\#.*)?")
                                    .matcher(link.resolvedUri())
                                    .replaceAll("https://api.github.com/repos/$1/$2/contents/$4?ref=$3$5");
                            log.debugf("Mapped:\n    %s -> \n    %s", link.resolvedUri(), result);
                            return link.mapToUri(result);
                        })
                        .endGroup()

                        .validate()
                        .stream())
                .isEmpty();

    }

    @Test
    void linkMapper() {
        RestAssured.delete("http://localhost:8084/accessLog")
                .then()
                .statusCode(201);

        Assertions.assertThat(
                links(
                        "http://localhost:8084/rateLimit/1/50/0",
                        "http://localhost:8084/rateLimit/1/50/1",
                        "http://localhost:8084/rateLimit/1/50/2",
                        "http://localhost:8084/rateLimit/1/50/3")

                        .group("http://localhost:8084/rateLimit/1/50/.*")
                        .linkMapper(link -> link
                                .mapToUri(link.resolvedUri().replace("http://localhost:8084/rateLimit/1/50/",
                                        "http://localhost:8084/constant/200/")))
                        .endGroup()

                        .validate()
                        .stream())
                .isEmpty();

        List<String> accessLog = Arrays.asList(RestAssured.get("http://localhost:8084/accessLog")
                .then()
                .statusCode(200)
                .extract().body().asString().split(","));

        Assertions.assertThat(accessLog)
                .containsExactlyInAnyOrder(
                        "/constant/200/0 200",
                        "/constant/200/1 200",
                        "/constant/200/2 200",
                        "/constant/200/3 200");

    }

    @BeforeAll
    static void beforeAll() {
        final Vertx vertx = Vertx.vertx();

        Router router = Router.router(vertx);
        router.route().handler(BodyHandler.create());

        final Map<String, long[]> rateLimits = new HashMap<>();
        final List<String> accessLog = new ArrayList<>();
        final BiConsumer<RoutingContext, Integer> accessLogger = (context, statusCode) -> {
            log.info("Accessed " + context.normalizedPath() + " " + statusCode);
            synchronized (accessLog) {
                accessLog.add(context.normalizedPath() + " " + statusCode);
            }
        };
        router.delete("/accessLog").handler(context -> {
            synchronized (accessLog) {
                accessLog.clear();
            }
            rateLimits.clear();
            context.response().setStatusCode(201).end();
        });
        router.get("/accessLog").handler(context -> {
            final String logString;
            synchronized (accessLog) {
                logString = accessLog.stream().collect(Collectors.joining(","));
            }
            context.response().setStatusCode(200).end(logString);
        });

        final AtomicInteger retryCounter = new AtomicInteger();
        router.get("/alternate/:even/:odd/:retryAfter").handler(context -> {
            boolean return503 = retryCounter.getAndIncrement() % 2 == 0;
            final int statusCode = return503 ? Integer.parseInt(context.pathParam("even"))
                    : Integer.parseInt(context.pathParam("odd"));
            accessLogger.accept(context, statusCode);
            context.response()
                    .setStatusCode(statusCode)
                    .putHeader(return503 ? "Retry-After" : "X-Foo", context.pathParam("retryAfter"))
                    .end(return503
                            ? ""
                            : """
                                    <!DOCTYPE html>
                                    <html lang="en">
                                    <head>
                                      <meta charset="UTF-8">
                                      <title>Hello</title>
                                    </head>
                                    <body>
                                      <h1>Hello, world!</h1>
                                    </body>
                                    </html>
                                                """);
        });

        router.get("/constant/:status/:retryAfter").handler(context -> {
            final int statusCode = Integer.parseInt(context.pathParam("status"));
            accessLogger.accept(context, statusCode);
            context.response()
                    .setStatusCode(statusCode)
                    .putHeader("Retry-After", context.pathParam("retryAfter"))
                    .end("");
        });

        router.get("/fragment/javaDoc").handler(context -> {
            final int statusCode = 200;
            accessLogger.accept(context, statusCode);
            context.response()
                    .setStatusCode(statusCode)
                    .end("""
                                    <!DOCTYPE html>
                                    <html lang="en">
                                    <head>
                                      <meta charset="UTF-8">
                                      <title>Hello</title>
                                    </head>
                                    <body>
                                      <section class="detail" id="parse(java.lang.CharSequence)"></section>
                                    </body>
                                    </html>

                            """);
        });

        router.get("/basicAuth/:random").handler(context -> {
            final String authHeader = context.request().getHeader(HttpHeaders.AUTHORIZATION);
            final int statusCode;
            if (authHeader == null || !authHeader.startsWith("Basic ")) {
                statusCode = 401;
            } else {
                String base64Credentials = authHeader.substring("Basic ".length()).trim();
                String[] parts = new String(Base64.getDecoder().decode(base64Credentials)).split(":", 2);
                if (parts.length == 2 && USERNAME.equals(parts[0]) && PASSWORD.equals(parts[1])) {
                    statusCode = 200;
                } else {
                    statusCode = 401;
                }
            }
            context.response()
                    .setStatusCode(statusCode)
                    .putHeader("Retry-After", context.pathParam("retryAfter"))
                    .end("");
            accessLogger.accept(context, statusCode);
        });

        router.get("/bearerToken/:random").handler(context -> {
            final String authHeader = context.request().getHeader(HttpHeaders.AUTHORIZATION);
            final int statusCode;
            if (authHeader == null || !authHeader.startsWith("Bearer ")) {
                statusCode = 401;
            } else {
                String token = authHeader.substring("Bearer ".length()).trim();
                if (TOKEN.equals(token)) {
                    statusCode = 200;
                } else {
                    statusCode = 401;
                }
            }
            accessLogger.accept(context, statusCode);
            context.response()
                    .setStatusCode(statusCode)
                    .putHeader("Retry-After", context.pathParam("retryAfter"))
                    .end("");
        });

        router.get("/sleep/:delay/:random").handler(context -> {
            final int statusCode = 200;
            final long delay = Long.parseLong(context.pathParam("delay"));
            log.infof("Sleeping %d ms", delay);
            accessLogger.accept(context, statusCode);
            vertx.setTimer(
                    delay,
                    id -> context.response()
                            .setStatusCode(statusCode)
                            .putHeader("Retry-After", context.pathParam("retryAfter"))
                            .end("Timed"));
        });

        router.get("/rateLimit/:requestCount/:timeMs/:random").handler(context -> {
            final int requestCount = Integer.parseInt(context.pathParam("requestCount"));
            final long timeMs = Long.parseLong(context.pathParam("timeMs"));
            final String key = "" + requestCount + "_" + timeMs;
            final long[] rate = rateLimits.computeIfAbsent(key, k -> new long[] { 0, System.currentTimeMillis() + timeMs });
            final boolean pass;
            synchronized (rate) {
                final long now = System.currentTimeMillis();
                // log.infof("rate for %s before %s, diff %d", key, Arrays.toString(rate), rate[1] - now);
                if (rate[1] <= now) {
                    rate[1] = now + timeMs;
                    rate[0] = 1;
                    pass = true;
                } else {
                    pass = ++rate[0] <= requestCount;
                }
                // log.infof("rate for %s after %s, diff %d", key, Arrays.toString(rate), rate[1] - now);
            }
            final int statusCode = pass ? 200 : 429;
            accessLogger.accept(context, statusCode);
            context.response()
                    .setStatusCode(statusCode)
                    .end(pass ? "passed" : "");
        });

        server = vertx.createHttpServer(new HttpServerOptions())
                .requestHandler(router)
                .listen(8084)
                .toCompletionStage()
                .toCompletableFuture()
                .join();

    }

    @AfterAll
    static void afterAll() {
        try {
            if (server != null) {
                server.close().toCompletionStage().toCompletableFuture().join();
            }
        } catch (Exception e) {
            // ignored
        }

    }

    static LinkStream links(String... links) {
        ResourceResolver resourceResolver = new ResourceResolver() {
            @Override
            public Link resolveUri(Path file, String uri) {
                return null;
            }

            @Override
            public Path resolveLocal(Path relativeToBranchDirectory) {
                return null;
            }

            @Override
            public URI getBaseUri() {
                return null;
            }

            @Override
            public SourceLocation findSource(Link link, Path absHtmlPath) {
                return null;
            }
        };
        return new LinkStream(Stream.of(links).map(Link::ofResolved), resourceResolver, 1, 30_000L);
    }

}
