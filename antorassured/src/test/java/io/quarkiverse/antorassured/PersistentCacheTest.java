package io.quarkiverse.antorassured;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.assertj.core.api.Assertions;
import org.assertj.core.api.Assumptions;
import org.jboss.logmanager.ExtLogRecord;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.quarkiverse.antorassured.LinkValidator.LinkValidatorImpl;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.http.HttpServerResponse;

/**
 * Tests the persistent cache of {@link LinkValidatorImpl} against a local HTTP server, using a temporary cache directory
 * and a controllable clock.
 */
public class PersistentCacheTest {
    private static final String TOKEN = "s3cr3t-t0k3n-deadbeef";
    private static final Instant NOW = Instant.parse("2026-11-20T15:29:13Z");
    private static final Charset LATIN2 = Charset.forName("ISO-8859-2");
    private static final byte[] LATIN2_BODY = "<html><body><p id=\"\u017elu\u0165ou\u010dk\u00fd\">P\u0159\u00edli\u0161</p></body></html>"
            .getBytes(LATIN2);
    private static final byte[] BINARY_BODY = new byte[512];
    static {
        for (int i = 0; i < BINARY_BODY.length; i++) {
            BINARY_BODY[i] = (byte) i;
        }
    }

    private static Vertx vertx;
    private static HttpServer server;
    private static int port;
    /** {@code http://localhost:<port>} */
    private static String baseUri;
    /** Path with query and status code, such as {@code /page/foo?bar 200} */
    private static final List<String> accessLog = new ArrayList<>();
    private static final Map<String, AtomicInteger> hitsByUri = new ConcurrentHashMap<>();

    @TempDir
    Path tempDir;
    private Path cacheDir;
    private final TestClock clock = new TestClock(NOW);
    private LogCollector logs;

    @Test
    void reuseAcrossValidators() {
        final String uri = baseUri + "/page/reuse";
        final ValidationResult first = validator().validate(req(uri));
        Assertions.assertThat(first.isValid()).isTrue();
        Assertions.assertThat(requests("/page/reuse")).isEqualTo(1);

        final ValidationResult second = validator().validate(req(uri));
        Assertions.assertThat(second).isEqualTo(first);
        Assertions.assertThat(second.statusCode()).isEqualTo(200);
        Assertions.assertThat(requests("/page/reuse")).isEqualTo(1);
        Assertions.assertThat(accessLog()).hasSize(1);
        Assertions.assertThat(logs.messages(Level.FINE)).anyMatch(m -> m.startsWith("Cache miss: " + uri))
                .anyMatch(m -> m.startsWith("Cache entry written: " + uri))
                .anyMatch(m -> m.startsWith("Cache hit: " + uri));
    }

    @Test
    void fragment() {
        final String uri = baseUri + "/page/fragment";
        validator().validate(req(uri)).assertValid();

        final LinkValidator cached = validator();
        Assertions.assertThat(cached.validate(req(uri + "#existing")).isValid()).isTrue();
        final ValidationResult missing = cached.validate(req(uri + "#missing"));
        Assertions.assertThat(missing.isInvalid()).isTrue();
        Assertions.assertThat(requests("/page/fragment")).isEqualTo(1);

        final ValidationResult missingWithoutCache = validator(u -> false).validate(req(uri + "#missing"));
        Assertions.assertThat(requests("/page/fragment")).isEqualTo(2);
        Assertions.assertThat(missing).isEqualTo(missingWithoutCache);
        Assertions.assertThat(missing.message()).isEqualTo("Could not find #missing");
    }

    @Test
    void nothingReadOnCreation() {
        final String uri = baseUri + "/page/late";
        final LinkValidator early = validator();
        Assertions.assertThat(cacheDir).doesNotExist();

        validator().validate(req(uri)).assertValid();
        Assertions.assertThat(requests("/page/late")).isEqualTo(1);

        /* The entry written after the creation of the validator is used */
        early.validate(req(uri)).assertValid();
        Assertions.assertThat(requests("/page/late")).isEqualTo(1);
    }

    @Test
    void defaults() {
        final PersistentCache cache = new LinkValidatorImpl().persistentCache();
        Assertions.assertThat(cache.root())
                .isEqualTo(Path.of(System.getProperty("user.home"), ".cache", "antorassured", "pages").toAbsolutePath());
        Assertions.assertThat(cache.expiration()).isEqualTo(Duration.ofHours(36));
        Assertions.assertThat(cache.gcAge()).isEqualTo(Duration.ofDays(180));
        Assertions.assertThat(cache.lockRetryDelay()).isEqualTo(Duration.ofSeconds(1));
        Assertions.assertThat(cache.lockRetries()).isEqualTo(3);

        final PersistentCache defaultValidatorCache = ((LinkValidatorImpl) LinkValidator.defaultValidator()).persistentCache();
        Assertions.assertThat(defaultValidatorCache.root()).isEqualTo(cache.root());
    }

    @Test
    void layout() throws IOException {
        final String uri = baseUri + "/dir1/dir2/file.html?querystring";
        validator().validate(req(uri)).assertValid();

        final Path entryDir = cacheDir.resolve("localhost/http/" + port + "/no-accept/dir1/dir2/file.html_querystring");
        Assertions.assertThat(entryDir).isDirectory();
        Assertions.assertThat(cacheFiles()).containsExactlyInAnyOrder(
                "localhost/http/" + port + "/no-accept/dir1/dir2/file.html_querystring/response---antorassured.properties",
                "localhost/http/" + port + "/no-accept/dir1/dir2/file.html_querystring/body---antorassured.txt",
                "localhost/http/" + port + "/no-accept/dir1/dir2/file.html_querystring/entry---antorassured.lock");

        final Properties props = metadata(entryDir);
        Assertions.assertThat(props).containsOnlyKeys("uri", "statusCode", "charset", "contentType", "timestamp");
        Assertions.assertThat(props.getProperty("uri")).isEqualTo(uri);
        Assertions.assertThat(props.getProperty("statusCode")).isEqualTo("200");
        Assertions.assertThat(props.getProperty("charset")).isEqualTo("UTF-8");
        Assertions.assertThat(props.getProperty("contentType")).isEqualTo("text/html; charset=UTF-8");
        Assertions.assertThat(props.getProperty("timestamp")).isEqualTo("2026-11-20T15:29:13Z");
        Assertions.assertThat(Files.readString(entryDir.resolve(PersistentCache.METADATA_FILE))).doesNotContain("\r");

        Assertions.assertThat(entryDir.resolve(PersistentCache.BODY_FILE))
                .hasBinaryContent(html("/dir1/dir2/file.html?querystring", 1).getBytes(StandardCharsets.UTF_8));
        Assertions.assertThat(entryDir.resolve(PersistentCache.LOCK_FILE)).isEmptyFile();
    }

    @Test
    void nonUtf8AndBinaryBody() throws IOException {
        final String latin2Uri = baseUri + "/latin2";
        final String binaryUri = baseUri + "/binary";
        final LinkValidator validator = validator();
        validator.validate(req(latin2Uri)).assertValid();
        validator.validate(req(binaryUri)).assertValid();

        final Path latin2Dir = entryDir("latin2");
        Assertions.assertThat(latin2Dir.resolve(PersistentCache.BODY_FILE)).hasBinaryContent(LATIN2_BODY);
        Assertions.assertThat(metadata(latin2Dir).getProperty("charset")).isEqualTo("ISO-8859-2");
        Assertions.assertThat(metadata(latin2Dir).getProperty("contentType")).isEqualTo("text/html; charset=ISO-8859-2");

        final Path binaryDir = entryDir("binary");
        Assertions.assertThat(binaryDir.resolve(PersistentCache.BODY_FILE)).hasBinaryContent(BINARY_BODY);
        Assertions.assertThat(metadata(binaryDir).getProperty("contentType")).isEqualTo("application/octet-stream");

        /* The stored charset is used for decoding the stored body */
        final LinkValidator cached = validator();
        Assertions.assertThat(cached.validate(req(latin2Uri + "#\u017elu\u0165ou\u010dk\u00fd")).isValid()).isTrue();
        Assertions.assertThat(cached.validate(req(latin2Uri + "#missing")).isInvalid()).isTrue();
        Assertions.assertThat(cached.validate(req(binaryUri)).isValid()).isTrue();
        Assertions.assertThat(accessLog()).containsExactly("/latin2 200", "/binary 200");
    }

    @Test
    void noContentType() throws IOException {
        final String uri = baseUri + "/plain/no-content-type";
        validator().validate(req(uri)).assertValid();
        Assertions.assertThat(metadata(entryDir("plain/no-content-type")))
                .containsOnlyKeys("uri", "statusCode", "charset", "timestamp");
        validator().validate(req(uri)).assertValid();
        Assertions.assertThat(requests("/plain/no-content-type")).isEqualTo(1);
    }

    @Test
    void windowsLineEnds() throws IOException {
        final String uri = baseUri + "/page/crlf";
        validator().validate(req(uri)).assertValid();
        final Path metadataFile = entryDir("page/crlf").resolve(PersistentCache.METADATA_FILE);
        final String metadata = Files.readString(metadataFile);
        Assertions.assertThat(metadata).contains("\n").doesNotContain("\r");
        Files.writeString(metadataFile, metadata.replace("\n", "\r\n"));

        validator().validate(req(uri + "#existing")).assertValid();
        Assertions.assertThat(requests("/page/crlf")).isEqualTo(1);
    }

    @Test
    void acceptHeader() {
        final String uri = baseUri + "/accept/doc";
        final LinkGroup raw = LinkStream.createDefaultGroup()
                .bearerToken(TOKEN)
                .header("Accept", "application/vnd.github.raw+json");
        final LinkGroup html = LinkStream.createDefaultGroup()
                .bearerToken(TOKEN)
                .header("Accept", "application/vnd.github.html+json");

        validator().validate(req(uri, raw)).assertValid();
        Assertions.assertThat(accessLog()).containsExactly("/accept/doc 200");
        /* An entry stored for another Accept header is not used */
        validator().validate(req(uri, html)).assertValid();
        Assertions.assertThat(accessLog()).containsExactly("/accept/doc 200", "/accept/doc 200");

        final String acceptParent = "localhost/http/" + port + "/";
        Assertions.assertThat(cacheFiles())
                .contains(
                        acceptParent + "application_vnd.github.raw+json/accept/doc/" + PersistentCache.METADATA_FILE,
                        acceptParent + "application_vnd.github.html+json/accept/doc/" + PersistentCache.METADATA_FILE)
                .hasSize(6);
        Assertions.assertThat(
                cacheDir.resolve(acceptParent + "application_vnd.github.raw+json/accept/doc/" + PersistentCache.BODY_FILE))
                .hasContent("application/vnd.github.raw+json");
        Assertions.assertThat(
                cacheDir.resolve(acceptParent + "application_vnd.github.html+json/accept/doc/" + PersistentCache.BODY_FILE))
                .hasContent("application/vnd.github.html+json");

        /* Both are used by new validators, the header name is case insensitive */
        validator().validate(req(uri, raw)).assertValid();
        validator().validate(req(uri, LinkStream.createDefaultGroup().header("accept", "application/vnd.github.html+json")))
                .assertValid();
        /* Changing only the Authorization header still hits */
        validator().validate(req(uri, LinkStream.createDefaultGroup()
                .bearerToken("another-token")
                .header("Accept", "application/vnd.github.raw+json"))).assertValid();
        validator().validate(req(uri, LinkStream.createDefaultGroup()
                .header("Accept", "application/vnd.github.raw+json"))).assertValid();
        Assertions.assertThat(accessLog()).hasSize(2);

        /* No Accept header is yet another entry */
        validator().validate(req(uri)).assertValid();
        Assertions.assertThat(accessLog()).hasSize(3);
    }

    @Test
    void failuresNotStored() throws IOException {
        for (int status : List.of(429, 500, 503, 404, 401, 403, 301, 410)) {
            final String path = "/status/" + status + "/x";
            final ValidationResult result = validator().validate(req(baseUri + path));
            Assertions.assertThat(result.isInvalid()).as(path).isTrue();
            Assertions.assertThat(result.statusCode()).as(path).isEqualTo(status);
            assertCacheEmpty();

            /* A new validator sends a request again */
            Assertions.assertThat(validator().validate(req(baseUri + path)).isInvalid()).as(path).isTrue();
            Assertions.assertThat(requests(path)).as(path).isEqualTo(2);
            assertCacheEmpty();
        }

        /* Connection failure */
        final int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        final ValidationResult refused = validator().validate(req("http://localhost:" + closedPort + "/page/refused"));
        Assertions.assertThat(refused.isInvalid()).isTrue();
        Assertions.assertThat(refused.message()).startsWith("Unable to connect");
        assertCacheEmpty();

        /* Unknown host */
        final ValidationResult unknownHost = validator().validate(req("http://antorassured-no-such-host.invalid/page"));
        Assertions.assertThat(unknownHost.isInvalid()).isTrue();
        assertCacheEmpty();

        /* Timeout */
        for (int i = 0; i < 2; i++) {
            final LinkValidatorImpl impatient = validator();
            impatient.jsoupSession.timeout(300);
            final ValidationResult timeout = impatient.validate(req(baseUri + "/slow/3000/timeout"));
            Assertions.assertThat(timeout.isInvalid()).isTrue();
            Assertions.assertThat(timeout.message()).containsIgnoringCase("timeout");
            Assertions.assertThat(requests("/slow/3000/timeout")).isEqualTo(i + 1);
            assertCacheEmpty();
        }

        /* A positive control: the same checks do see an entry */
        validator().validate(req(baseUri + "/page/control")).assertValid();
        Assertions.assertThat(cacheFiles()).hasSize(3);
    }

    @Test
    void loopbackNotStoredByDefault() {
        for (String host : List.of("localhost", "127.0.0.1", "LOCALHOST")) {
            final String uri = "http://" + host + ":" + port + "/page/loopback";
            for (int i = 0; i < 2; i++) {
                final LinkValidator validator = new LinkValidatorImpl(
                        cacheDir,
                        LinkValidatorImpl.DEFAULT_CACHE_EXPIRATION,
                        LinkValidatorImpl.DEFAULT_CACHE_GC_AGE,
                        LinkValidatorImpl.DEFAULT_CACHE_LOCK_RETRY_DELAY,
                        LinkValidatorImpl.DEFAULT_CACHE_LOCK_RETRIES,
                        LinkValidatorImpl.notLoopback(),
                        clock);
                validator.validate(req(uri)).assertValid();
                validator.validationFinished();
            }
        }
        Assertions.assertThat(requests("/page/loopback")).isEqualTo(6);
        Assertions.assertThat(cacheDir).doesNotExist();
    }

    /**
     * {@link LinkStream#validate()} uses the default cache directory, which is under {@code target} thanks to
     * {@code user.home} set in {@code pom.xml}.
     */
    @Test
    void siteUnderTestNotStored() throws IOException {
        final Path defaultCacheDir = LinkValidatorImpl.defaultCacheDirectory().toAbsolutePath().normalize();
        Assumptions.assumeThat(defaultCacheDir.startsWith(Path.of("target").toAbsolutePath()))
                .as("user.home should be under target but the default cache directory is %s", defaultCacheDir)
                .isTrue();
        final String address = nonLoopbackAddress();
        Assumptions.assumeThat(address).as("This test requires a non-loopback IPv4 address").isNotNull();

        final String id = UUID.randomUUID().toString();
        final String siteBaseUri = "http://" + address + ":" + port + "/page/site-" + id + "/dev/";
        final String sitePath = "/page/site-" + id + "/dev/index.html";
        final String otherPath = "/page/other-" + id + "/index.html";
        final String reversedAddress = Stream.of(address.split("\\.")).reduce((a, b) -> b + "/" + a).get();
        final Path acceptDir = defaultCacheDir.resolve(reversedAddress + "/http/" + port + "/no-accept");

        for (int i = 1; i <= 2; i++) {
            Assertions.assertThat(
                    new LinkStream(
                            Stream.of("http://" + address + ":" + port + sitePath + "#existing",
                                    "http://" + address + ":" + port + otherPath + "#existing")
                                    .map(Link::ofResolved),
                            resourceResolver(URI.create(siteBaseUri)),
                            1,
                            30_000L)
                            .validate()
                            .stream())
                    .isEmpty();
            /* The site under test is fetched always */
            Assertions.assertThat(requests(sitePath)).isEqualTo(i);
            Assertions.assertThat(acceptDir.resolve("page/site-" + id)).doesNotExist();
            /* Other pages on the same host are stored */
            Assertions.assertThat(requests(otherPath)).isEqualTo(1);
            Assertions.assertThat(acceptDir.resolve("page/other-" + id + "/index.html/" + PersistentCache.METADATA_FILE))
                    .isRegularFile();
        }
    }

    @Test
    void expiration() throws IOException {
        final String path = "/page/expiring";
        final String uri = baseUri + path;
        final Path entryDir = entryDir("page/expiring");
        validator().validate(req(uri + "#hit-1")).assertValid();
        Assertions.assertThat(requests(path)).isEqualTo(1);

        /* Just before the expiration */
        clock.set(NOW.plus(Duration.ofHours(36)));
        validator().validate(req(uri + "#hit-1")).assertValid();
        Assertions.assertThat(requests(path)).isEqualTo(1);
        Assertions.assertThat(metadata(entryDir).getProperty("timestamp")).isEqualTo("2026-11-20T15:29:13Z");

        /* Past the expiration */
        clock.set(NOW.plus(Duration.ofHours(36)).plusSeconds(1));
        validator().validate(req(uri + "#hit-2")).assertValid();
        Assertions.assertThat(requests(path)).isEqualTo(2);
        Assertions.assertThat(metadata(entryDir).getProperty("timestamp")).isEqualTo("2026-11-22T03:29:14Z");
        Assertions.assertThat(entryDir.resolve(PersistentCache.BODY_FILE)).hasContent(html(path, 2));
        Assertions.assertThat(logs.messages(Level.FINE)).anyMatch(m -> m.startsWith("Cache entry expired: " + uri));

        /* The new entry is used */
        validator().validate(req(uri + "#hit-2")).assertValid();
        Assertions.assertThat(requests(path)).isEqualTo(2);

        /* Custom expiration */
        clock.set(clock.instant().plus(Duration.ofMinutes(59)));
        validator(Duration.ofHours(1), LinkValidatorImpl.DEFAULT_CACHE_GC_AGE).validate(req(uri)).assertValid();
        Assertions.assertThat(requests(path)).isEqualTo(2);
        clock.set(clock.instant().plus(Duration.ofMinutes(2)));
        validator(Duration.ofHours(1), LinkValidatorImpl.DEFAULT_CACHE_GC_AGE).validate(req(uri + "#hit-3")).assertValid();
        Assertions.assertThat(requests(path)).isEqualTo(3);
        /* ... while the same entry would still be good with the default expiration */
        validator().validate(req(uri + "#hit-3")).assertValid();
        Assertions.assertThat(requests(path)).isEqualTo(3);
    }

    @Test
    void outdatedEntryNotUsedIfRefetchFails() {
        final String path = "/sequence/200-503/stale";
        final String uri = baseUri + path;
        validator().validate(req(uri)).assertValid();

        clock.set(NOW.plus(Duration.ofHours(37)));
        final ValidationResult result = validator().validate(req(uri));
        final ValidationResult withoutCache = validator(u -> false).validate(req(baseUri + "/status/503/stale"));
        Assertions.assertThat(result.isInvalid()).isTrue();
        Assertions.assertThat(result.statusCode()).isEqualTo(503);
        Assertions.assertThat(result.message()).isEqualTo(withoutCache.message()).isEqualTo("503, Retry-After: null");
        Assertions.assertThat(result.shouldRetry()).isEqualTo(withoutCache.shouldRetry()).isTrue();
        Assertions.assertThat(accessLog()).containsExactly(path + " 200", path + " 503", "/status/503/stale 503");

        /* ... and it is not used later either */
        Assertions.assertThat(validator().validate(req(uri)).statusCode()).isEqualTo(503);
        Assertions.assertThat(requests(path)).isEqualTo(3);
    }

    @Test
    void unusableEntries() throws IOException {
        final Map<String, EntryModifier> modifiers = new LinkedHashMap<>();
        modifiers.put("missing-body", dir -> Files.delete(dir.resolve(PersistentCache.BODY_FILE)));
        modifiers.put("missing-metadata", dir -> Files.delete(dir.resolve(PersistentCache.METADATA_FILE)));
        modifiers.put("missing-both", dir -> {
            Files.delete(dir.resolve(PersistentCache.BODY_FILE));
            Files.delete(dir.resolve(PersistentCache.METADATA_FILE));
        });
        modifiers.put("unparsable-metadata",
                dir -> Files.writeString(dir.resolve(PersistentCache.METADATA_FILE), "uri=\\uZZZZ\ntimestamp"));
        modifiers.put("binary-metadata", dir -> Files.write(dir.resolve(PersistentCache.METADATA_FILE), BINARY_BODY));
        modifiers.put("empty-metadata", dir -> Files.writeString(dir.resolve(PersistentCache.METADATA_FILE), ""));
        modifiers.put("metadata-is-directory", dir -> {
            Files.delete(dir.resolve(PersistentCache.METADATA_FILE));
            Files.createDirectory(dir.resolve(PersistentCache.METADATA_FILE));
        });
        modifiers.put("missing-timestamp", dir -> removeProperty(dir, "timestamp"));
        modifiers.put("malformed-timestamp", dir -> replaceProperty(dir, "timestamp", "yesterday"));
        modifiers.put("unknown-charset", dir -> replaceProperty(dir, "charset", "NO-SUCH-CHARSET"));
        modifiers.put("illegal-charset", dir -> replaceProperty(dir, "charset", "?"));
        modifiers.put("missing-charset", dir -> removeProperty(dir, "charset"));
        modifiers.put("missing-status-code", dir -> removeProperty(dir, "statusCode"));
        modifiers.put("malformed-status-code", dir -> replaceProperty(dir, "statusCode", "OK"));
        modifiers.put("non-2xx-status-code", dir -> replaceProperty(dir, "statusCode", "404"));
        modifiers.put("missing-uri", dir -> removeProperty(dir, "uri"));
        modifiers.put("different-uri", dir -> replaceProperty(dir, "uri", baseUri + "/page/another"));
        modifiers.put("different-query", dir -> replaceProperty(dir, "uri", baseUri + "/page/different-query?foo"));
        modifiers.put("unparsable-uri", dir -> replaceProperty(dir, "uri", "foo"));
        modifiers.put("leftover-temporary-files", dir -> {
            Files.move(dir.resolve(PersistentCache.BODY_FILE), dir.resolve(PersistentCache.BODY_FILE + ".tmp"));
            Files.move(dir.resolve(PersistentCache.METADATA_FILE), dir.resolve(PersistentCache.METADATA_FILE + ".tmp"));
        });

        for (Map.Entry<String, EntryModifier> modifier : modifiers.entrySet()) {
            final String name = modifier.getKey();
            final String path = "/page/" + name;
            final String uri = baseUri + path;
            final Path entryDir = entryDir("page/" + name);
            validator().validate(req(uri + "#hit-1")).assertValid();
            Assertions.assertThat(requests(path)).as(name).isEqualTo(1);
            modifier.getValue().modify(entryDir);

            /* The broken entry is a miss */
            final ValidationResult result = validator().validate(req(uri + "#hit-2"));
            Assertions.assertThat(result.isValid()).as(name + ": " + result).isTrue();
            Assertions.assertThat(requests(path)).as(name).isEqualTo(2);

            /* A usable entry is left behind */
            Assertions.assertThat(metadata(entryDir).getProperty("uri")).as(name).isEqualTo(uri);
            Assertions.assertThat(entryDir.resolve(PersistentCache.BODY_FILE)).as(name).hasContent(html(path, 2));
            validator().validate(req(uri + "#hit-2")).assertValid();
            Assertions.assertThat(requests(path)).as(name).isEqualTo(2);
        }
        /* Unusable entries are no reason for warnings */
        Assertions.assertThat(logs.messages(Level.WARNING)).isEmpty();
    }

    @Test
    void equivalentUrisShareEntry() {
        validator().validate(req(baseUri + "/page/dir/")).assertValid();
        Assertions.assertThat(accessLog()).containsExactly("/page/dir/ 200");
        for (String uri : List.of(
                baseUri + "/page/dir",
                baseUri + "/page/x/../dir",
                "HTTP://LocalHost:" + port + "/page/./dir/")) {
            validator().validate(req(uri)).assertValid();
        }
        Assertions.assertThat(accessLog()).containsExactly("/page/dir/ 200");
    }

    @Test
    void collidingUrisEvictEachOther() throws IOException {
        /* Both map to page/collision_a */
        final String uri1 = baseUri + "/page/collision?a";
        final String uri2 = baseUri + "/page/collision_a";
        final Path entryDir = entryDir("page/collision_a");

        validator().validate(req(uri1)).assertValid();
        Assertions.assertThat(metadata(entryDir).getProperty("uri")).isEqualTo(uri1);
        validator().validate(req(uri2)).assertValid();
        Assertions.assertThat(metadata(entryDir).getProperty("uri")).isEqualTo(uri2);
        Assertions.assertThat(entryDir.resolve(PersistentCache.BODY_FILE)).hasContent(html("/page/collision_a", 1));
        validator().validate(req(uri2)).assertValid();
        validator().validate(req(uri1)).assertValid();
        Assertions.assertThat(accessLog())
                .containsExactly("/page/collision?a 200", "/page/collision_a 200", "/page/collision?a 200");
        Assertions.assertThat(cacheFiles()).hasSize(3);
    }

    @Test
    void rateLimit() {
        final int count = 5;
        final String[] uris = new String[count];
        for (int i = 0; i < count; i++) {
            uris[i] = baseUri + "/page/limited/" + i;
        }
        final String groupPattern = "http://localhost:[0-9]+/page/limited/.*";

        /* A control: without the cache, the second URI is delayed */
        final LinkGroup limitedGroup = LinkStream.createDefaultGroup().pattern(groupPattern)
                .rateLimit(RateLimit.requestsPerTimeInterval(1, 60_000));
        final LinkValidator validator = validator();
        Assertions.assertThat(validator.validate(req(uris[0], limitedGroup)).isValid()).isTrue();
        final ValidationResult delayed = validator.validate(req(uris[1], limitedGroup));
        Assertions.assertThat(delayed.message()).startsWith("Delayed ");
        Assertions.assertThat(delayed.shouldRetry()).isTrue();
        Assertions.assertThat(accessLog()).containsExactly("/page/limited/0 200");
        /* The delayed URI leaves nothing behind */
        Assertions.assertThat(cacheFiles()).hasSize(3);

        /* Store all */
        final LinkValidator storing = validator();
        for (String uri : uris) {
            storing.validate(req(uri)).assertValid();
        }
        Assertions.assertThat(accessLog()).hasSize(count);

        final long start = System.currentTimeMillis();
        Assertions.assertThat(
                LinkStreamTest.links(uris)
                        .group(groupPattern)
                        .rateLimit(RateLimit.requestsPerTimeInterval(1, 60_000))
                        .finalPolicy(AggregatePolicy.countAtLeast(200, count))
                        .finalPolicy(AggregatePolicy.countAtMost(200, count))
                        .endGroup()
                        .validate(validator())
                        .stream()
                        .map(ValidationResult::toString))
                .isEmpty();
        Assertions.assertThat(System.currentTimeMillis() - start).isLessThan(10_000L);
        Assertions.assertThat(accessLog()).hasSize(count);

        /* Different fragments of a page already known to the validator do not consume the rate limit either */
        final LinkValidator sameValidator = validator();
        final LinkGroup group = LinkStream.createDefaultGroup().pattern(groupPattern)
                .rateLimit(RateLimit.requestsPerTimeInterval(1, 60_000));
        for (int i = 0; i < 3; i++) {
            Assertions.assertThat(sameValidator.validate(req(uris[0] + "#hit-1", group)).isValid()).isTrue();
            Assertions.assertThat(sameValidator.validate(req(uris[0] + "#existing", group)).isValid()).isTrue();
        }
        Assertions.assertThat(group.stats().getResponseCountByStatus(200)).isEqualTo(2);
    }

    @Test
    void switchedOff() throws IOException {
        final String uri = baseUri + "/page/off";
        for (int i = 1; i <= 2; i++) {
            Assertions.assertThat(LinkStreamTest.links(uri).validate(validator(u -> false)).stream()).isEmpty();
            Assertions.assertThat(requests("/page/off")).isEqualTo(i);
            Assertions.assertThat(cacheDir).doesNotExist();
        }

        /* A populated directory is neither read nor garbage collected */
        clock.set(NOW.minus(Duration.ofDays(181)));
        validator().validate(req(uri)).assertValid();
        Assertions.assertThat(requests("/page/off")).isEqualTo(3);
        clock.set(NOW);
        final List<String> files = cacheFiles();
        Assertions.assertThat(files).hasSize(3);

        Assertions.assertThat(LinkStreamTest.links(uri).validate(validator(u -> false)).stream()).isEmpty();
        Assertions.assertThat(requests("/page/off")).isEqualTo(4);
        Assertions.assertThat(cacheFiles()).isEqualTo(files);

        /* A control: the entry is garbage collected when the cache is on */
        Assertions.assertThat(LinkStreamTest.links(baseUri + "/page/on").validate(validator()).stream()).isEmpty();
        Assertions.assertThat(cacheFiles()).doesNotContainAnyElementsOf(files).hasSize(3);
    }

    @Test
    void failingPredicate() {
        final String uri = baseUri + "/page/failing-predicate";
        final ValidationResult result = validator(u -> {
            throw new IllegalStateException("Thrown by the predicate");
        }).validate(req(uri));
        Assertions.assertThat(result.isValid()).isTrue();
        Assertions.assertThat(cacheDir).doesNotExist();
        Assertions.assertThat(logs.messages(Level.WARNING)).anyMatch(m -> m.contains("Thrown by the predicate"));
    }

    @Test
    void concurrentValidators() throws Exception {
        final String path = "/slow/1000/concurrent";
        final String uri = baseUri + path;
        final int processCount = 2;
        final Path goFile = tempDir.resolve("go");
        final List<Path> readyFiles = new ArrayList<>();
        final List<Path> outputs = new ArrayList<>();
        final List<Process> processes = new ArrayList<>();
        try {
            for (int i = 0; i < processCount; i++) {
                final Path readyFile = tempDir.resolve("ready-" + i);
                final Path output = tempDir.resolve("process-" + i + ".log");
                readyFiles.add(readyFile);
                outputs.add(output);
                processes.add(
                        new ProcessBuilder(
                                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                                "-cp",
                                System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                                "-Djava.util.logging.manager=org.jboss.logmanager.LogManager",
                                "-Dorg.jboss.logging.provider=jboss",
                                PersistentCacheTestClient.class.getName(),
                                cacheDir.toString(),
                                uri,
                                "4",
                                readyFile.toString(),
                                goFile.toString())
                                .redirectErrorStream(true)
                                .redirectOutput(output.toFile())
                                .start());
            }

            final List<String> errors = PersistentCacheTestClient.validateConcurrently(cacheDir, uri, 8, () -> {
                final long deadline = System.currentTimeMillis() + 60_000L;
                while (!readyFiles.stream().allMatch(Files::exists)) {
                    Assertions.assertThat(processes).as(output(outputs)).allMatch(Process::isAlive);
                    Assertions.assertThat(System.currentTimeMillis()).as(output(outputs)).isLessThan(deadline);
                    Thread.sleep(10);
                }
                Files.createFile(goFile);
                return null;
            });
            Assertions.assertThat(errors).isEmpty();
            for (Process process : processes) {
                Assertions.assertThat(process.waitFor(60, TimeUnit.SECONDS)).as(output(outputs)).isTrue();
                Assertions.assertThat(process.exitValue()).as(output(outputs)).isEqualTo(0);
            }
        } finally {
            processes.forEach(Process::destroyForcibly);
        }
        final String output = output(outputs);
        /* Everybody was validating at the same time and only one of them fetched */
        Assertions.assertThat(accessLog()).as(output).containsExactly(path + " 200");
        Assertions.assertThat(countMatches(output, "Cache entry written: " + uri)
                + logs.messages(Level.FINE).stream().filter(m -> m.startsWith("Cache entry written: " + uri)).count())
                .as(output).isEqualTo(1);
        Assertions.assertThat(countMatches(output, "Cache hit: " + uri)
                + logs.messages(Level.FINE).stream().filter(m -> m.startsWith("Cache hit: " + uri)).count())
                .as(output).isEqualTo(8 + processCount * 4 - 1);
        Assertions.assertThat(cacheFiles()).hasSize(3);
    }

    @Test
    void incompleteEntries() throws IOException {
        final Path entryDir = entryDir("page/incomplete");
        final String path = "/page/incomplete";
        final String uri = baseUri + path;
        validator().validate(req(uri)).assertValid();
        final byte[] body = Files.readAllBytes(entryDir.resolve(PersistentCache.BODY_FILE));
        final byte[] metadata = Files.readAllBytes(entryDir.resolve(PersistentCache.METADATA_FILE));

        /* Only the body */
        Files.delete(entryDir.resolve(PersistentCache.METADATA_FILE));
        Assertions.assertThat(validator().validate(req(uri)).isValid()).isTrue();
        Assertions.assertThat(requests(path)).isEqualTo(2);

        /* Only the metadata */
        Files.delete(entryDir.resolve(PersistentCache.BODY_FILE));
        Assertions.assertThat(validator().validate(req(uri)).isValid()).isTrue();
        Assertions.assertThat(requests(path)).isEqualTo(3);

        /* Only temporary files, as if the process was killed before any of them was moved */
        Files.delete(entryDir.resolve(PersistentCache.BODY_FILE));
        Files.delete(entryDir.resolve(PersistentCache.METADATA_FILE));
        Files.write(entryDir.resolve(PersistentCache.BODY_FILE + ".tmp"), body);
        Files.write(entryDir.resolve(PersistentCache.METADATA_FILE + ".tmp"), metadata);
        Assertions.assertThat(validator().validate(req(uri)).isValid()).isTrue();
        Assertions.assertThat(requests(path)).isEqualTo(4);

        /* The body moved and the process killed before the metadata was moved */
        Files.delete(entryDir.resolve(PersistentCache.METADATA_FILE));
        Files.write(entryDir.resolve(PersistentCache.METADATA_FILE + ".tmp"), metadata);
        Assertions.assertThat(validator().validate(req(uri)).isValid()).isTrue();
        Assertions.assertThat(requests(path)).isEqualTo(5);

        /* No temporary files are left behind */
        validator().validate(req(uri)).assertValid();
        Assertions.assertThat(requests(path)).isEqualTo(5);
        Assertions.assertThat(cacheFiles()).hasSize(3);
    }

    @Test
    void rootIsFile() throws IOException {
        final Path file = tempDir.resolve("file.txt");
        Files.writeString(file, "foo");
        assertUnusableRoot(file);
        assertUnusableRoot(file.resolve("pages"));
        Assertions.assertThat(file).hasContent("foo");
    }

    @Test
    void rootNotWritable() throws IOException {
        Assumptions.assumeThat(Files.getFileStore(tempDir).supportsFileAttributeView("posix")).isTrue();
        final Path readOnly = Files.createDirectory(tempDir.resolve("read-only"));
        Files.setPosixFilePermissions(readOnly, PosixFilePermissions.fromString("r-xr-xr-x"));
        try {
            /* root can write anywhere */
            Assumptions.assumeThat(Files.isWritable(readOnly)).isFalse();
            assertUnusableRoot(readOnly);
            assertUnusableRoot(readOnly.resolve("pages"));
            try (Stream<Path> files = Files.list(readOnly)) {
                Assertions.assertThat(files).isEmpty();
            }
        } finally {
            Files.setPosixFilePermissions(readOnly, PosixFilePermissions.fromString("rwxr-xr-x"));
        }
    }

    private void assertUnusableRoot(Path root) {
        final String id = UUID.randomUUID().toString();
        final String path = "/page/" + id;
        logs.clear();
        final ValidationResult withoutCache = validator(u -> false).validate(req(baseUri + path + "#missing"));
        for (int i = 2; i <= 3; i++) {
            final LinkValidator validator = validator(root, u -> true);
            Assertions.assertThat(validator.validate(req(baseUri + path)).isValid()).isTrue();
            Assertions.assertThat(validator.validate(req(baseUri + path + "#existing")).isValid()).isTrue();
            Assertions.assertThat(validator.validate(req(baseUri + path + "#missing"))).isEqualTo(withoutCache);
            Assertions.assertThat(validator.validate(req(baseUri + "/status/404/" + id)).statusCode()).isEqualTo(404);
            validator.validationFinished();
            Assertions.assertThat(requests(path)).isEqualTo(i);
        }
        Assertions.assertThat(logs.messages(Level.WARNING))
                .filteredOn(m -> m.startsWith("Not caching " + baseUri + path + ":"))
                .hasSize(2);
    }

    @Test
    void lockRetrySucceeds() throws Exception {
        final String path = "/page/retry";
        final String uri = baseUri + path;
        validator().validate(req(uri)).assertValid();
        final Path lockFile = entryDir("page/retry").resolve(PersistentCache.LOCK_FILE);
        final long delay = 500L;

        final LinkValidator validator = validator(Duration.ofMillis(delay), 3);
        final long start;
        final CompletableFuture<ValidationResult> result;
        try (FileChannel channel = FileChannel.open(lockFile, StandardOpenOption.WRITE); FileLock lock = channel.lock()) {
            logs.clear();
            start = System.nanoTime();
            result = CompletableFuture.supplyAsync(() -> validator.validate(req(uri)));
            /* Release during the second wait */
            Thread.sleep(delay + delay / 2);
            Assertions.assertThat(result).isNotDone();
        }
        Assertions.assertThat(result.get(30, TimeUnit.SECONDS).isValid()).isTrue();
        final long duration = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        final List<String> retries = logs.messages(Level.FINE).stream().filter(m -> m.startsWith("Retry ")).toList();
        Assertions.assertThat(retries).isNotEmpty();
        Assertions.assertThat(retries.get(0)).startsWith("Retry 1 of 3 in 500 ms: lock for " + uri);
        /* Each wait is at least the configured delay long */
        Assertions.assertThat(duration).isGreaterThanOrEqualTo(retries.size() * delay);
        Assertions.assertThat(retries.size()).isBetween(2, 3);
        /* The stored entry was used */
        Assertions.assertThat(requests(path)).isEqualTo(1);
        Assertions.assertThat(logs.messages(Level.FINE)).anyMatch(m -> m.startsWith("Cache hit: " + uri));
        Assertions.assertThat(logs.messages(Level.WARNING)).isEmpty();
    }

    @Test
    void lockRetriesExhausted() throws Exception {
        final String path = "/page/locked";
        final String uri = baseUri + path;
        validator().validate(req(uri)).assertValid();
        final Path entryDir = entryDir("page/locked");
        final byte[] body = Files.readAllBytes(entryDir.resolve(PersistentCache.BODY_FILE));
        int requests = 1;

        try (FileChannel channel = FileChannel.open(entryDir.resolve(PersistentCache.LOCK_FILE), StandardOpenOption.WRITE);
                FileLock lock = channel.lock()) {
            /* { delay in ms, retries } */
            for (int[] settings : new int[][] { { 200, 3 }, { 200, 0 }, { 50, 5 }, { 400, 1 } }) {
                final long delay = settings[0];
                final int retries = settings[1];
                final String description = "delay " + delay + ", retries " + retries;
                logs.clear();
                final long start = System.nanoTime();
                final ValidationResult result = validator(Duration.ofMillis(delay), retries).validate(req(uri + "#existing"));
                final long duration = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

                /* Validated by fetching */
                Assertions.assertThat(result.isValid()).as(description).isTrue();
                Assertions.assertThat(requests(path)).as(description).isEqualTo(++requests);
                /* 1 + retries attempts */
                Assertions.assertThat(logs.messages(Level.FINE).stream().filter(m -> m.startsWith("Retry ")))
                        .as(description)
                        .hasSize(retries)
                        .allMatch(m -> m.contains(" of " + retries + " in " + delay + " ms"));
                Assertions.assertThat(duration).as(description).isGreaterThanOrEqualTo(retries * delay);
                Assertions.assertThat(logs.messages(Level.WARNING)).as(description)
                        .hasSize(1)
                        .allMatch(m -> m.startsWith("Not caching " + uri + ": could not lock"));
            }
        }
        /* The entry was not touched */
        Assertions.assertThat(entryDir.resolve(PersistentCache.BODY_FILE)).hasBinaryContent(body);
        validator().validate(req(uri)).assertValid();
        Assertions.assertThat(requests(path)).isEqualTo(requests);
    }

    @Test
    void garbageCollection() throws IOException {
        final List<String> events = Collections.synchronizedList(new ArrayList<>());
        final Path oldDir = entryDir("page/gc/old/a/b");
        final Path youngDir = entryDir("page/gc/young/a");
        final Path noTimestampDir = entryDir("page/gc/no-timestamp/a");

        clock.set(NOW.minus(Duration.ofDays(181)));
        validator().validate(req(baseUri + "/page/gc/old/a/b")).assertValid();
        validator().validate(req(baseUri + "/page/gc/no-timestamp/a")).assertValid();
        replaceProperty(noTimestampDir, "timestamp", "long ago");
        clock.set(NOW.minus(Duration.ofDays(179)));
        validator().validate(req(baseUri + "/page/gc/young/a")).assertValid();
        clock.set(NOW);

        final Path foreignFileInRoot = Files.writeString(cacheDir.resolve("foreign.txt"), "foo");
        final Path foreignDir = Files.createDirectories(cacheDir.resolve("foreign/empty"));
        final Path foreignFileInEntryParent = Files.writeString(entryDir("page/gc/foreign.txt"), "bar");
        final List<String> filesBefore = cacheFiles();
        Assertions.assertThat(filesBefore).hasSize(11);

        /* A run over an empty link stream removes nothing */
        final LinkValidatorImpl usedValidator = validator(Duration.ofDays(365), LinkValidatorImpl.DEFAULT_CACHE_GC_AGE);
        usedValidator.validate(req(baseUri + "/page/gc/young/a")).assertValid();
        Assertions.assertThat(LinkStreamTest.links().validate(usedValidator).stream()).isEmpty();
        Assertions.assertThat(cacheFiles()).isEqualTo(filesBefore);

        final LinkValidatorImpl validator = new LinkValidatorImpl(
                cacheDir,
                LinkValidatorImpl.DEFAULT_CACHE_EXPIRATION,
                LinkValidatorImpl.DEFAULT_CACHE_GC_AGE,
                Duration.ofMillis(200),
                3,
                u -> true,
                clock) {
            @Override
            public ValidationResult validate(ValidationRequest req) {
                final ValidationResult result = super.validate(req);
                events.add("validated " + req.link().resolvedUri() + " " + Files.exists(oldDir));
                return result;
            }

            @Override
            public void validationFinished() {
                events.add("finished " + Files.exists(oldDir));
                super.validationFinished();
                events.add("collected " + Files.exists(oldDir));
            }
        };
        Assertions.assertThat(
                LinkStreamTest
                        .links(baseUri + "/page/gc/trigger/1", baseUri + "/page/gc/trigger/2", baseUri + "/retry/503/1/gc")
                        .retryAttempts(1)
                        .validate(validator)
                        .stream()
                        .map(ValidationResult::statusCode))
                .containsExactly(503);
        /* Collected once, after the last link was validated, including the retries */
        Assertions.assertThat(events).containsExactly(
                "validated " + baseUri + "/page/gc/trigger/1 true",
                "validated " + baseUri + "/page/gc/trigger/2 true",
                "validated " + baseUri + "/retry/503/1/gc true",
                "validated " + baseUri + "/retry/503/1/gc true",
                "finished true",
                "collected false");

        /* The old entry and the directories emptied by its removal are gone */
        Assertions.assertThat(entryDir("page/gc/old")).doesNotExist();
        Assertions.assertThat(entryDir("page/gc")).isDirectory();
        Assertions.assertThat(logs.messages(Level.FINE)).anyMatch(m -> m.equals("Garbage collected " + oldDir));
        /* The rest is untouched */
        final List<String> expectedFiles = new ArrayList<>(filesBefore);
        expectedFiles.removeIf(f -> f.contains("/gc/old/"));
        Assertions.assertThat(expectedFiles).hasSize(8);
        final String triggerDir = cacheDir.relativize(entryDir("page/gc/trigger")).toString().replace('\\', '/');
        for (int i = 1; i <= 2; i++) {
            expectedFiles.add(triggerDir + "/" + i + "/" + PersistentCache.METADATA_FILE);
            expectedFiles.add(triggerDir + "/" + i + "/" + PersistentCache.BODY_FILE);
            expectedFiles.add(triggerDir + "/" + i + "/" + PersistentCache.LOCK_FILE);
        }
        Assertions.assertThat(cacheFiles()).containsExactlyInAnyOrderElementsOf(expectedFiles);
        Assertions.assertThat(youngDir.resolve(PersistentCache.METADATA_FILE)).isRegularFile();
        Assertions.assertThat(noTimestampDir.resolve(PersistentCache.METADATA_FILE)).isRegularFile();
        Assertions.assertThat(noTimestampDir.resolve(PersistentCache.BODY_FILE)).isRegularFile();
        Assertions.assertThat(foreignFileInRoot).hasContent("foo");
        Assertions.assertThat(foreignFileInEntryParent).hasContent("bar");
        Assertions.assertThat(foreignDir).isDirectory();

        /* A custom collection age */
        final LinkValidator customAge = validator(LinkValidatorImpl.DEFAULT_CACHE_EXPIRATION, Duration.ofDays(178));
        Assertions.assertThat(LinkStreamTest.links(baseUri + "/page/gc/trigger/1").validate(customAge).stream()).isEmpty();
        Assertions.assertThat(entryDir("page/gc/young")).doesNotExist();
        Assertions.assertThat(entryDir("page/gc/trigger/1").resolve(PersistentCache.METADATA_FILE)).isRegularFile();
        Assertions.assertThat(noTimestampDir.resolve(PersistentCache.METADATA_FILE)).isRegularFile();
        Assertions.assertThat(foreignFileInEntryParent).hasContent("bar");
        Assertions.assertThat(logs.messages(Level.WARNING).stream().filter(m -> !m.contains("503"))).isEmpty();
    }

    @Test
    void garbageCollectionInSymlinkedRoot() throws IOException {
        final Path link = tempDir.resolve("link");
        try {
            Files.createDirectories(cacheDir);
            Files.createSymbolicLink(link, cacheDir);
        } catch (IOException | UnsupportedOperationException e) {
            Assumptions.assumeThat(e).as("Symbolic links not supported").isNull();
        }
        clock.set(NOW.minus(Duration.ofDays(181)));
        validator(link, u -> true).validate(req(baseUri + "/page/symlink/old")).assertValid();
        clock.set(NOW);
        Assertions.assertThat(cacheFiles()).hasSize(3);

        final LinkValidator validator = validator(link, u -> true);
        validator.validate(req(baseUri + "/page/symlink/new")).assertValid();
        validator.validationFinished();
        Assertions.assertThat(cacheFiles()).hasSize(3).allMatch(f -> f.contains("/page/symlink/new/"));
        Assertions.assertThat(link).isSymbolicLink();
    }

    @Test
    void garbageCollectionSkipsLockedEntries() throws Exception {
        clock.set(NOW.minus(Duration.ofDays(181)));
        for (String name : List.of("a", "b", "c")) {
            validator().validate(req(baseUri + "/page/gc-locked/" + name)).assertValid();
        }
        clock.set(NOW);
        final Path lockedDir = entryDir("page/gc-locked/b");

        /* Long delays would make the retries visible */
        final LinkValidator validator = validator(Duration.ofSeconds(20), 3);
        validator.validate(req(baseUri + "/page/gc-locked/trigger")).assertValid();
        final long start = System.nanoTime();
        try (FileChannel channel = FileChannel.open(lockedDir.resolve(PersistentCache.LOCK_FILE), StandardOpenOption.WRITE);
                FileLock lock = channel.lock()) {
            validator.validationFinished();
        }
        Assertions.assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)).isLessThan(10_000L);
        Assertions.assertThat(logs.messages(Level.FINE)).noneMatch(m -> m.startsWith("Retry "))
                .anyMatch(m -> m.equals("Garbage collection skipping locked " + lockedDir));
        Assertions.assertThat(cacheFiles().stream().filter(f -> f.endsWith(PersistentCache.METADATA_FILE))
                .map(f -> f.substring(f.indexOf("/page/"))))
                .containsExactlyInAnyOrder(
                        "/page/gc-locked/b/" + PersistentCache.METADATA_FILE,
                        "/page/gc-locked/trigger/" + PersistentCache.METADATA_FILE);

        /* An entry locked by the current class loader is skipped too */
        final PersistentCache cache = validator().persistentCache();
        final CacheKey key = CacheKey.of(baseUri + "/page/gc-locked/b", null);
        try (PersistentCache.EntryLock lock = cache.lock(key)) {
            CompletableFuture.runAsync(validator::validationFinished).get(10, TimeUnit.SECONDS);
        }
        Assertions.assertThat(lockedDir.resolve(PersistentCache.METADATA_FILE)).isRegularFile();

        /* It is collected by a later run */
        validator.validationFinished();
        Assertions.assertThat(lockedDir).doesNotExist();
        Assertions.assertThat(logs.messages(Level.WARNING)).isEmpty();
    }

    @Test
    void noCredentialsStored() throws IOException {
        final String basicCredentials = java.util.Base64.getEncoder().encodeToString("joe:secret1234".getBytes());
        final LinkValidator validator = validator();
        validator.validate(req(baseUri + "/auth/bearer", LinkStream.createDefaultGroup()
                .bearerToken(TOKEN)
                .header("X-Custom", "custom-header-value")
                .header("Accept", "text/html"))).assertValid();
        validator.validate(req(baseUri + "/page/basic", LinkStream.createDefaultGroup().basicAuth("joe", "secret1234")))
                .assertValid();
        /* URIs with user info leave no entry */
        validator.validate(req("http://joe:secret1234@localhost:" + port + "/page/user-info"));
        validator.validate(req("http://joe@localhost:" + port + "/page/user-info"));

        final List<String> files = cacheFiles();
        Assertions.assertThat(files).hasSize(6).noneMatch(f -> f.contains("user-info"));
        Assertions.assertThat(files.stream().filter(f -> f.endsWith(PersistentCache.METADATA_FILE)))
                .containsExactlyInAnyOrder(
                        "localhost/http/" + port + "/text_html/auth/bearer/" + PersistentCache.METADATA_FILE,
                        "localhost/http/" + port + "/no-accept/page/basic/" + PersistentCache.METADATA_FILE);
        for (String file : files) {
            final String content = Files.readString(cacheDir.resolve(file), StandardCharsets.ISO_8859_1);
            Assertions.assertThat(file + "\n" + content)
                    .doesNotContain(TOKEN)
                    .doesNotContain("Bearer")
                    .doesNotContain(basicCredentials)
                    .doesNotContain("secret1234")
                    .doesNotContain("custom-header-value");
        }
    }

    @BeforeAll
    static void beforeAll() {
        vertx = Vertx.vertx();
        server = vertx.createHttpServer(new HttpServerOptions())
                .requestHandler(request -> {
                    final String uri = request.uri();
                    final String[] segments = request.path().split("/");
                    final int hit = hitsByUri.computeIfAbsent(uri, k -> new AtomicInteger()).incrementAndGet();
                    final HttpServerResponse response = request.response();
                    final String kind = segments.length > 1 ? segments[1] : "";
                    switch (kind) {
                        case "page":
                        case "dir1":
                            log(uri, 200);
                            response.putHeader(HttpHeaders.CONTENT_TYPE, "text/html; charset=UTF-8").end(html(uri, hit));
                            break;
                        case "plain":
                            log(uri, 200);
                            response.end(html(uri, hit));
                            break;
                        case "status": {
                            final int status = Integer.parseInt(segments[2]);
                            log(uri, status);
                            response.setStatusCode(status).end(status / 100 == 2 ? html(uri, hit) : "");
                            break;
                        }
                        case "retry": {
                            final int status = Integer.parseInt(segments[2]);
                            log(uri, status);
                            response.setStatusCode(status).putHeader("Retry-After", segments[3]).end("");
                            break;
                        }
                        case "sequence": {
                            final String[] statuses = segments[2].split("-");
                            final int status = Integer.parseInt(statuses[Math.min(hit, statuses.length) - 1]);
                            log(uri, status);
                            response.setStatusCode(status).end(status / 100 == 2 ? html(uri, hit) : "");
                            break;
                        }
                        case "latin2":
                            log(uri, 200);
                            response.putHeader(HttpHeaders.CONTENT_TYPE, "text/html; charset=ISO-8859-2")
                                    .end(io.vertx.core.buffer.Buffer.buffer(LATIN2_BODY));
                            break;
                        case "binary":
                            log(uri, 200);
                            response.putHeader(HttpHeaders.CONTENT_TYPE, "application/octet-stream")
                                    .end(io.vertx.core.buffer.Buffer.buffer(BINARY_BODY));
                            break;
                        case "slow":
                            log(uri, 200);
                            vertx.setTimer(Long.parseLong(segments[2]), id -> response.end(html(uri, hit)));
                            break;
                        case "auth": {
                            final int status = ("Bearer " + TOKEN).equals(request.getHeader(HttpHeaders.AUTHORIZATION)) ? 200
                                    : 401;
                            log(uri, status);
                            response.setStatusCode(status).end(status == 200 ? html(uri, hit) : "");
                            break;
                        }
                        case "accept":
                            log(uri, 200);
                            response.putHeader(HttpHeaders.CONTENT_TYPE, "text/plain")
                                    .end(String.valueOf(request.getHeader(HttpHeaders.ACCEPT)));
                            break;
                        default:
                            log(uri, 404);
                            response.setStatusCode(404).end();
                            break;
                    }
                })
                .listen(0)
                .toCompletionStage()
                .toCompletableFuture()
                .join();
        port = server.actualPort();
        baseUri = "http://localhost:" + port;
    }

    @AfterAll
    static void afterAll() {
        try {
            if (server != null) {
                server.close().toCompletionStage().toCompletableFuture().join();
            }
            if (vertx != null) {
                vertx.close().toCompletionStage().toCompletableFuture().join();
            }
        } catch (Exception e) {
            // ignored
        }
    }

    @BeforeEach
    void beforeEach() {
        cacheDir = tempDir.resolve("cache").resolve("pages");
        synchronized (accessLog) {
            accessLog.clear();
        }
        hitsByUri.clear();
        logs = new LogCollector();
    }

    @AfterEach
    void afterEach() {
        logs.close();
    }

    private static void log(String uri, int status) {
        synchronized (accessLog) {
            accessLog.add(uri + " " + status);
        }
    }

    static List<String> accessLog() {
        synchronized (accessLog) {
            return new ArrayList<>(accessLog);
        }
    }

    /**
     * @param pathAndQuery the path, possibly with a query, as requested from the server
     * @return the number of requests for the given {@code pathAndQuery} received by the server
     */
    static long requests(String pathAndQuery) {
        return accessLog().stream().filter(entry -> entry.startsWith(pathAndQuery + " ")).count();
    }

    static String html(String uri, int hit) {
        return "<!DOCTYPE html>\n<html lang=\"en\"><head><meta charset=\"UTF-8\"><title>Hello</title></head><body>"
                + "<h1 id=\"existing\">Hello</h1><p id=\"hit-" + hit + "\">" + uri + "</p></body></html>\n";
    }

    LinkValidatorImpl validator() {
        return validator(cacheDir, u -> true);
    }

    LinkValidatorImpl validator(Predicate<String> cacheable) {
        return validator(cacheDir, cacheable);
    }

    LinkValidatorImpl validator(Path root, Predicate<String> cacheable) {
        return new LinkValidatorImpl(
                root,
                LinkValidatorImpl.DEFAULT_CACHE_EXPIRATION,
                LinkValidatorImpl.DEFAULT_CACHE_GC_AGE,
                Duration.ofMillis(200),
                3,
                cacheable,
                clock);
    }

    LinkValidatorImpl validator(Duration expiration, Duration gcAge) {
        return new LinkValidatorImpl(cacheDir, expiration, gcAge, Duration.ofMillis(200), 3, u -> true, clock);
    }

    LinkValidatorImpl validator(Duration lockRetryDelay, int lockRetries) {
        return new LinkValidatorImpl(
                cacheDir,
                LinkValidatorImpl.DEFAULT_CACHE_EXPIRATION,
                LinkValidatorImpl.DEFAULT_CACHE_GC_AGE,
                lockRetryDelay,
                lockRetries,
                u -> true,
                clock);
    }

    static ValidationRequest req(String uri) {
        return req(uri, LinkStream.createDefaultGroup());
    }

    static ValidationRequest req(String uri, LinkGroup group) {
        return new ValidationRequest(Link.ofResolved(uri), 2, group);
    }

    /**
     * @param path the path of the URI without the leading slash
     * @return the directory of the entry for the given {@code path} requested from the test server without {@code Accept}
     *         header
     */
    Path entryDir(String path) {
        return cacheDir.resolve("localhost/http/" + port + "/no-accept/" + path);
    }

    /**
     * @return the regular files under {@link #cacheDir} relative to it, with {@code /} as the delimiter
     */
    List<String> cacheFiles() {
        return files(cacheDir);
    }

    static List<String> files(Path dir) {
        if (!Files.exists(dir)) {
            return List.of();
        }
        try (Stream<Path> files = Files.walk(dir)) {
            return files
                    .filter(Files::isRegularFile)
                    .map(f -> dir.relativize(f).toString().replace('\\', '/'))
                    .sorted()
                    .collect(Collectors.toList());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Asserts that there are no files and no directories under {@link #cacheDir}.
     */
    void assertCacheEmpty() throws IOException {
        if (Files.exists(cacheDir)) {
            try (Stream<Path> files = Files.list(cacheDir)) {
                Assertions.assertThat(files).isEmpty();
            }
        }
    }

    static Properties metadata(Path entryDir) throws IOException {
        final Properties result = new Properties();
        try (InputStream in = Files.newInputStream(entryDir.resolve(PersistentCache.METADATA_FILE))) {
            result.load(in);
        }
        return result;
    }

    static void removeProperty(Path entryDir, String key) throws IOException {
        replaceProperty(entryDir, key, null);
    }

    static void replaceProperty(Path entryDir, String key, String newValue) throws IOException {
        final Path file = entryDir.resolve(PersistentCache.METADATA_FILE);
        final List<String> lines = new ArrayList<>(Files.readAllLines(file));
        Assertions.assertThat(lines.removeIf(line -> line.startsWith(key + "="))).isTrue();
        if (newValue != null) {
            lines.add(key + "=" + newValue);
        }
        Files.write(file, lines);
    }

    static String output(List<Path> files) {
        final StringBuilder result = new StringBuilder();
        for (Path file : files) {
            result.append(file.getFileName()).append(":\n");
            try {
                if (Files.exists(file)) {
                    result.append(Files.readString(file));
                }
            } catch (IOException e) {
                result.append(e);
            }
            result.append('\n');
        }
        return result.toString();
    }

    static int countMatches(String string, String substring) {
        int result = 0;
        for (int i = string.indexOf(substring); i >= 0; i = string.indexOf(substring, i + 1)) {
            result++;
        }
        return result;
    }

    static String nonLoopbackAddress() {
        try {
            for (NetworkInterface networkInterface : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (networkInterface.isUp() && !networkInterface.isLoopback()) {
                    for (InetAddress address : Collections.list(networkInterface.getInetAddresses())) {
                        if (address instanceof Inet4Address && !address.isLoopbackAddress()) {
                            return address.getHostAddress();
                        }
                    }
                }
            }
        } catch (IOException e) {
            // fall through
        }
        return null;
    }

    static ResourceResolver resourceResolver(URI baseUri) {
        return new ResourceResolver() {
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
                return baseUri;
            }

            @Override
            public SourceLocation findSource(Link link, Path absHtmlPath) {
                return null;
            }
        };
    }

    @FunctionalInterface
    interface EntryModifier {
        void modify(Path entryDir) throws IOException;
    }

    /** A {@link Clock} that can be set */
    static class TestClock extends Clock {
        private volatile Instant instant;

        TestClock(Instant instant) {
            this.instant = instant;
        }

        void set(Instant instant) {
            this.instant = instant;
        }

        @Override
        public Instant instant() {
            return instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }

    /** Collects what is logged through the {@link AntorAssured} logger */
    static class LogCollector extends Handler implements AutoCloseable {
        private final java.util.logging.Logger logger = java.util.logging.Logger.getLogger(AntorAssured.class.getName());
        private final Level originalLevel = logger.getLevel();
        private final List<LogRecord> records = Collections.synchronizedList(new ArrayList<>());

        LogCollector() {
            setLevel(Level.ALL);
            logger.setLevel(Level.FINE);
            logger.addHandler(this);
        }

        /**
         * @param level {@link Level#FINE} for {@code DEBUG} or {@link Level#WARNING} for {@code WARN}
         * @return the messages logged with the given {@code level}
         */
        List<String> messages(Level level) {
            synchronized (records) {
                return records.stream()
                        .filter(r -> r.getLevel().intValue() == level.intValue())
                        .map(r -> r instanceof ExtLogRecord ? ((ExtLogRecord) r).getFormattedMessage() : r.getMessage())
                        .collect(Collectors.toList());
            }
        }

        void clear() {
            records.clear();
        }

        @Override
        public void publish(LogRecord record) {
            records.add(record);
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
            logger.removeHandler(this);
            logger.setLevel(originalLevel);
        }
    }
}
