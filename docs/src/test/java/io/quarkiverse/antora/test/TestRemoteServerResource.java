/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.quarkiverse.antora.test;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.jboss.logging.Logger;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.MultiMap;
import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.http.RequestOptions;
import io.vertx.core.http.impl.headers.HeadersMultiMap;
import io.vertx.core.json.JsonArray;
import io.vertx.core.net.NetClient;
import io.vertx.core.net.NetSocket;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.handler.BodyHandler;

public class TestRemoteServerResource implements QuarkusTestResourceLifecycleManager {
    private static final Logger LOG = Logger.getLogger(TestRemoteServerResource.class);

    private HttpServer server;

    @Override
    public Map<String, String> start() {
        final Vertx vertx = Vertx.vertx();

        final AtomicInteger reqCounter = new AtomicInteger();

        Router router = Router.router(vertx);
        router.route().handler(BodyHandler.create());
        router.get("/retry-me").handler(context -> {
            boolean return503 = reqCounter.getAndIncrement() % 2 == 0;
            context.response()
                    .setStatusCode(return503 ? 503 : 200)
                    .putHeader(return503 ? "Retry-After" : "X-Foo", "2")
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

        server = vertx.createHttpServer(new HttpServerOptions())
                .requestHandler(router)
                .listen(8083)
                .toCompletionStage()
                .toCompletableFuture()
                .join();

        return Map.of();
    }

    @Override
    public void stop() {
        try {
            if (server != null) {
                server.close().toCompletionStage().toCompletableFuture().join();
            }
        } catch (Exception e) {
            // ignored
        }
    }

    /**
     * Bare-bones HTTP proxy server implementation that supports authentication.
     */
    static final class ProxyServer implements Handler<HttpServerRequest> {
        private final int port;
        private final String proxyUser;
        private final String proxyPassword;
        private final Vertx vertx;
        private final HttpServer proxyServer;
        private final List<String> proxiedRequests = new ArrayList<>();

        ProxyServer(int port, String proxyUser, String proxyPassword) {
            this.port = port;
            this.proxyUser = proxyUser;
            this.proxyPassword = proxyPassword;
            this.vertx = Vertx.vertx(new VertxOptions().setWorkerPoolSize(1).setEventLoopPoolSize(1));
            this.proxyServer = vertx.createHttpServer();
        }

        void start() {
            CountDownLatch startLatch = new CountDownLatch(1);
            proxyServer.requestHandler(this);
            proxyServer.listen(port).onComplete(result -> {
                LOG.infof("HTTP proxy server started on port %d", port);
                startLatch.countDown();
            });
            try {
                startLatch.await(1, TimeUnit.MINUTES);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }

        void stop() {
            if (proxyServer != null) {
                LOG.info("HTTP proxy server shutting down");
                proxyServer.close();
            }
            if (vertx != null) {
                vertx.close();
            }
        }

        @Override
        public void handle(HttpServerRequest httpServerRequest) {
            String authorization = httpServerRequest.getHeader("Proxy-Authorization");
            HttpServerResponse response = httpServerRequest.response();
            HttpMethod method = httpServerRequest.method();
            if (method.equals(HttpMethod.CONNECT) && authorization == null) {
                response.putHeader("Proxy-Authenticate", "Basic")
                        .setStatusCode(407)
                        .end();
                return;
            } else if (method.equals(HttpMethod.GET)) {
                List<String> vals = new ArrayList<>();
                synchronized (proxiedRequests) {
                    vals.addAll(proxiedRequests);
                    proxiedRequests.clear();
                }
                httpServerRequest.response()
                        .putHeader("Content-Type", "application/json")
                        .end(new JsonArray(vals).encodePrettily());
                return;
            }

            if (authorization != null) {
                String[] authParts = authorization.split(" ");
                String[] credentials = new String(Base64.getDecoder().decode(authParts[1])).split(":");
                if (credentials.length != 2) {
                    response.setStatusCode(400).end();
                    return;
                } else {
                    if (credentials[0].equals(proxyUser) && credentials[1].equals(proxyPassword)) {
                        String host = httpServerRequest.getHeader("Host");
                        String[] hostParts = host.split(":");
                        final String remoteHost = hostParts[0];
                        final int remotePort = Integer.parseInt(hostParts[1]);

                        if (method.equals(HttpMethod.CONNECT)) {
                            // Deal with the result of the CONNECT tunnel and proxy the request / response
                            NetClient netClient = vertx.createNetClient();
                            netClient.connect(remotePort, remoteHost, result -> {
                                if (result.succeeded()) {
                                    NetSocket clientSocket = result.result();
                                    Future<NetSocket> netSocket = httpServerRequest.toNetSocket();
                                    NetSocket serverSocket = netSocket.result();
                                    serverSocket.closeHandler(v -> clientSocket.close());
                                    clientSocket.closeHandler(v -> serverSocket.close());
                                    serverSocket.pipeTo(clientSocket);
                                    clientSocket.pipeTo(serverSocket);
                                } else {
                                    response.setStatusCode(403).end();
                                }
                            });
                        } else {
                            // non-CONNECT

                            httpServerRequest.body().onSuccess(httpServerBody -> {
                                LOG.infof("Proxying to %s %s", httpServerRequest.uri(),
                                        httpServerBody);
                                synchronized (proxiedRequests) {
                                    proxiedRequests.add(method + " " + httpServerRequest.uri() + " " + httpServerBody);
                                }
                                HttpClient client = vertx.createHttpClient();
                                MultiMap remoteHeaders = new HeadersMultiMap();
                                remoteHeaders.addAll(httpServerRequest.headers());
                                remoteHeaders.remove("Proxy-Authorization");
                                client.request(
                                        new RequestOptions()
                                                .setMethod(method)
                                                .setHeaders(remoteHeaders)
                                                .setPort(remotePort)
                                                .setHost(remoteHost)
                                                .setURI(httpServerRequest.uri()))
                                        .onSuccess(remoteRequest -> {
                                            remoteRequest.end(httpServerBody);
                                            remoteRequest.response()
                                                    .onSuccess(remoteResponse -> {
                                                        // Forward the response status and headers
                                                        HttpServerResponse httpServerResponse = httpServerRequest.response();
                                                        httpServerResponse.setStatusCode(remoteResponse.statusCode());
                                                        httpServerResponse.headers().setAll(remoteResponse.headers());

                                                        // Pipe the response body
                                                        remoteResponse.body()
                                                                .onSuccess(body -> {
                                                                    httpServerResponse.end(body);
                                                                }).onFailure(err -> {
                                                                    LOG.errorf(err, "Could not receive body from %s to %:%s %s",
                                                                            method, remoteHost,
                                                                            remotePort, httpServerRequest.uri());
                                                                    httpServerResponse.setStatusCode(500)
                                                                            .end("Internal Server Error ");
                                                                });
                                                    })
                                                    .onFailure(err -> {
                                                        LOG.errorf(err, "Could not receive response from %s to %:%s %s", method,
                                                                remoteHost,
                                                                remotePort, httpServerRequest.uri());
                                                        httpServerRequest.response().setStatusCode(500)
                                                                .end("Internal Server Error ");
                                                    });
                                        })
                                        .onFailure(remoteError -> {
                                            LOG.errorf(remoteError, "Could not send request to %s to %:%s %s", method,
                                                    remoteHost,
                                                    remotePort, httpServerRequest.uri());
                                            httpServerRequest.response().setStatusCode(500)
                                                    .end("Internal Server Error ");

                                        });
                            })
                                    .onFailure(err -> {
                                        LOG.errorf(err, "Could not recevie the body from the proxy server client %s to %:%s %s",
                                                method, remoteHost,
                                                remotePort, httpServerRequest.uri());
                                        httpServerRequest.response().setStatusCode(500)
                                                .end("Internal Server Error ");
                                    });
                        }
                    }
                    return;
                }
            }
            response.setStatusCode(401).end();
        }
    }
}
