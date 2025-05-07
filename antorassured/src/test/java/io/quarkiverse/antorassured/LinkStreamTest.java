package io.quarkiverse.antorassured;

import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.assertj.core.api.Assertions;
import org.assertj.core.api.ListAssert;
import org.jboss.logging.Logger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import io.restassured.RestAssured;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.BodyHandler;

public class LinkStreamTest {
    private static final Logger log = Logger.getLogger(LinkStreamTest.class);
    private static HttpServer server;

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
                        "http://localhost:8084/constant/503/1: 503 Service Unavailable, Retry-After: 1, attempted 2 times");
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
                        "http://localhost:8084/constant/404/default: 404 Not Found, attempted -1 times",
                        "http://localhost:8084/constant/401/0: 401 Unauthorized, attempted -1 times",
                        "http://localhost:8084/constant/401/1: 401 Unauthorized, attempted -1 times",
                        "http://localhost:8084/constant/401/2: 401 Unauthorized, attempted -1 times",
                        "http://localhost:8084/constant/401/3: 401 Unauthorized, attempted -1 times",
                        "http://localhost:8084/constant/401/4: 401 Unauthorized, attempted -1 times",
                        "http://localhost:8084/constant/401/5: 401 Unauthorized, attempted -1 times",
                        "http://localhost:8084/constant/401/6: 401 Unauthorized, attempted -1 times",
                        "http://localhost:8084/constant/401/7: 401 Unauthorized, attempted -1 times",
                        "http://localhost:8084/constant/401/8: 401 Unauthorized, attempted -1 times",
                        "http://localhost:8084/constant/401/9: 401 Unauthorized, attempted -1 times");
        listAssert.element(0).isEqualTo("http://localhost:8084/constant/404/default: 404 Not Found, attempted -1 times");

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
