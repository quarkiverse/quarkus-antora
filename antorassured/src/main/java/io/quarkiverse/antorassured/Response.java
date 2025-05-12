package io.quarkiverse.antorassured;

import java.nio.charset.Charset;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import org.jboss.logging.Logger;
import org.jsoup.nodes.Document;
import org.jsoup.parser.Parser;

public class Response {
    private static final Logger log = Logger.getLogger(AntorAssured.class);
    private final String uri;
    private final int statusCode;
    private final String statusMessage;
    private final Charset charset;
    private final String contentType;
    private final byte[] body;
    private final Map<Class<?>, Object> transfromedBodies = new ConcurrentHashMap<>();

    public static Response none(String baseUri) {
        return new Response(baseUri, -1, null, null, null, null);
    }

    Response(String uri, int statusCode, String statusMessage, Charset charset, String contentType, byte[] body) {
        super();
        this.uri = uri;
        this.statusCode = statusCode;
        this.statusMessage = statusMessage;
        this.charset = charset;
        this.contentType = contentType;
        this.body = body;
    }

    public String uri() {
        return uri;
    }

    public int statusCode() {
        return statusCode;
    }

    public String statusMessage() {
        return statusMessage;
    }

    public Charset charset() {
        return charset;
    }

    public String contentType() {
        return contentType;
    }

    public byte[] body() {
        return body;
    }

    public <T> T bodyAs(Class<T> cl, Function<Response, T> transformer) {
        return (T) transfromedBodies.computeIfAbsent(cl, k -> transformer.apply(this));
    }

    public Document bodyAsHtmlDocument() {
        final String bodyString = bodyAsString();
        return bodyAs(Document.class, resp -> Parser.htmlParser().parseInput(bodyString, resp.uri));
    }

    public String bodyAsString() {
        return bodyAs(String.class, resp -> {
            final String result = new String(resp.body, resp.charset);
            log.tracef("Body of %s:\n%s", uri, result);
            return result;
        });
    }

}