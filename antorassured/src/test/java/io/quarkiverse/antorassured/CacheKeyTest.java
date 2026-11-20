package io.quarkiverse.antorassured;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

import io.quarkiverse.antorassured.LinkValidator.LinkValidatorImpl;

public class CacheKeyTest {

    private static final Set<String> WINDOWS_DEVICE_NAMES = Set.of(
            "CON", "PRN", "AUX", "NUL",
            "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
            "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9");
    private static final String REJECTED_CHARS = "<>:\"\\|?*/";

    @Test
    void issueExample() {
        Assertions.assertThat(path("https://host.example.com:8080/dir1/dir2/file.html?querystring"))
                .isEqualTo("com/example/host/https/8080/no-accept/dir1/dir2/file.html_querystring");
    }

    @Test
    void defaultPort() {
        Assertions.assertThat(path("https://quarkus.io/x")).isEqualTo("io/quarkus/https/443/no-accept/x");
        Assertions.assertThat(path("http://quarkus.io/x")).isEqualTo("io/quarkus/http/80/no-accept/x");
        Assertions.assertThat(path("https://quarkus.io:443/x")).isEqualTo(path("https://quarkus.io/x"));
        Assertions.assertThat(path("https://quarkus.io:/x")).isEqualTo(path("https://quarkus.io/x"));
        Assertions.assertThat(path("https://quarkus.io:8443/x")).isEqualTo("io/quarkus/https/8443/no-accept/x");
    }

    @Test
    void trailingSlash() {
        Assertions.assertThat(path("https://quarkus.io/dir/")).isEqualTo(path("https://quarkus.io/dir"));
        Assertions.assertThat(path("https://h/")).isEqualTo(path("https://h")).isEqualTo("h/https/443/no-accept");
        Assertions.assertThat(CacheKey.normalize("https://h/")).isEqualTo(CacheKey.normalize("https://h"));
        Assertions.assertThat(CacheKey.normalize("https://h/dir/")).isEqualTo(CacheKey.normalize("https://h:443/dir"));
    }

    @Test
    void host() {
        Assertions.assertThat(path("http://127.0.0.1/x")).isEqualTo("1/0/0/127/http/80/no-accept/x");
        Assertions.assertThat(path("http://[::1]:8080/x")).isEqualTo("[__1]/http/8080/no-accept/x");
        Assertions.assertThat(path("HTTPS://Quarkus.IO/Guides")).isEqualTo("io/quarkus/https/443/no-accept/Guides");
        Assertions.assertThat(CacheKey.normalize("HTTPS://Quarkus.IO/Guides")).isEqualTo("https://quarkus.io:443/Guides");
    }

    @Test
    void dotSegments() {
        Assertions.assertThat(path("https://h/a/../b")).isEqualTo(path("https://h/b")).isEqualTo("h/https/443/no-accept/b");
        Assertions.assertThat(path("https://h/a/./b/.")).isEqualTo(path("https://h/a/b"));
        Assertions.assertThat(path("https://h/a/b/..")).isEqualTo(path("https://h/a"));
        Assertions.assertThat(CacheKey.normalize("https://h/a/../b")).isEqualTo(CacheKey.normalize("https://h/b"));
        /* Percent-encoded octets are not decoded */
        Assertions.assertThat(path("https://h/a/%2e%2e/b")).isEqualTo("h/https/443/no-accept/a/%2e%2e/b");
    }

    @Test
    void insideAcceptDirectory() {
        final Path root = Path.of("target/cache-key-test").toAbsolutePath().normalize();
        for (String uri : List.of(
                "https://h/../../x",
                "https://h/..",
                "https://h/../",
                "https://h/a/../../../../x",
                "https://h/..\\..\\x",
                "https://h/%2e%2e/%2e%2e/x",
                "https://h/.../x",
                "https://h/x?/../../../y",
                "https://h/..?..",
                "https://h/a//..//..//../x")) {
            final CacheKey key = CacheKey.of(uri, Map.of("Accept", List.of("../../..")));
            Assertions.assertThat(key).as(uri).isNotNull();
            final Path acceptDir = root.resolve(String.join("/", key.acceptDirectory())).normalize();
            Assertions.assertThat(acceptDir).as(uri).startsWithRaw(root).isNotEqualTo(root);
            Assertions.assertThat(acceptDir.getNameCount()).as(uri)
                    .isEqualTo(root.getNameCount() + key.acceptDirectory().size());
            Assertions.assertThat(key.resolve(root)).as(uri).startsWithRaw(acceptDir);
            assertPortable(key);
        }
        Assertions.assertThat(path("https://h/../../x")).isEqualTo("h/https/443/no-accept/x");
    }

    @Test
    void sanitize() {
        Assertions.assertThat(path("https://h/file.html?a=b:c*")).isEqualTo("h/https/443/no-accept/file.html_a=b_c_");

        Assertions.assertThat(path("https://h/aux")).isEqualTo("h/https/443/no-accept/aux_");
        Assertions.assertThat(path("https://h/NUL.txt")).isEqualTo("h/https/443/no-accept/NUL_.txt");
        Assertions.assertThat(path("https://h/com1/lpt9.tar.gz")).isEqualTo("h/https/443/no-accept/com1_/lpt9_.tar.gz");
        Assertions.assertThat(path("https://con.example.com/x")).isEqualTo("com/example/con_/https/443/no-accept/x");
        /* Not a device name */
        Assertions.assertThat(path("https://h/console/com10")).isEqualTo("h/https/443/no-accept/console/com10");

        Assertions.assertThat(path("https://h/dot./x")).isEqualTo("h/https/443/no-accept/dot_/x");
        Assertions.assertThat(path("https://h/space /x")).isEqualTo("h/https/443/no-accept/space_/x");
        Assertions.assertThat(path("https://h/x?dot.")).isEqualTo("h/https/443/no-accept/x_dot_");

        /* Empty segments */
        Assertions.assertThat(path("https://h/a//b")).isEqualTo("h/https/443/no-accept/a/_/b");
    }

    @Test
    void slashInQueryAndAccept() {
        Assertions.assertThat(path("https://h/a/b?c/d/e")).isEqualTo("h/https/443/no-accept/a/b_c_d_e");
        Assertions.assertThat(path("https://h?c/d")).isEqualTo("h/https/443/no-accept/_c_d");
        Assertions.assertThat(path("https://h/?c/d")).isEqualTo("h/https/443/no-accept/_c_d");

        Assertions.assertThat(CacheKey.of("https://h/a", Map.of("Accept", List.of("application/vnd.github.raw+json")))
                .relativePath())
                .isEqualTo("h/https/443/application_vnd.github.raw+json/a");
        Assertions.assertThat(CacheKey.of("https://h/a", Map.of("accept", List.of("text/html", "application/xhtml+xml")))
                .relativePath())
                .isEqualTo("h/https/443/text_html,application_xhtml+xml/a");
        Assertions.assertThat(
                CacheKey.of("https://h/a", Map.of("Authorization", List.of("Bearer foo"), "X-Foo", List.of("bar")))
                        .relativePath())
                .isEqualTo("h/https/443/no-accept/a");
        Assertions.assertThat(CacheKey.of("https://h/a", null).relativePath()).isEqualTo("h/https/443/no-accept/a");
    }

    @Test
    void longNames() {
        final String prefix = "a".repeat(299);
        final String name1 = CacheKey.of("https://h/" + prefix + "1", null).entryDirectory().get(0);
        final String name2 = CacheKey.of("https://h/" + prefix + "2", null).entryDirectory().get(0);
        Assertions.assertThat(name1.getBytes(StandardCharsets.UTF_8)).hasSize(255);
        Assertions.assertThat(name2.getBytes(StandardCharsets.UTF_8)).hasSize(255);
        Assertions.assertThat(name1).isNotEqualTo(name2).startsWith("a".repeat(236) + "---");
        Assertions.assertThat(CacheKey.of("https://h/" + prefix + "1", null).entryDirectory().get(0)).isEqualTo(name1);

        /* 255 bytes fit */
        Assertions.assertThat(CacheKey.of("https://h/" + "b".repeat(255), null).entryDirectory().get(0))
                .isEqualTo("b".repeat(255));

        /* Multi-byte characters are not split */
        final String multiByte = CacheKey.of("https://h/" + "\u017e".repeat(200) + "\ud83d\ude00".repeat(50), null)
                .entryDirectory().get(0);
        Assertions.assertThat(multiByte.getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(255);
        Assertions.assertThat(new String(multiByte.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8))
                .isEqualTo(multiByte);
        assertPortable(multiByte);

        /* A long query string and a long Accept header */
        assertPortable(CacheKey.of("https://h/a?" + "q=1&".repeat(100), Map.of("Accept", List.of("text/html;".repeat(40)))));
    }

    @Test
    void portableNames() {
        final List<String> specialStrings = new ArrayList<>();
        for (char c = 0; c < 0x20; c++) {
            specialStrings.add(String.valueOf(c));
        }
        specialStrings.add("\u007f");
        specialStrings.add("\u0085");
        specialStrings.add("\ud800"); // an unpaired surrogate
        for (char c : REJECTED_CHARS.toCharArray()) {
            specialStrings.add(String.valueOf(c));
        }
        specialStrings.add(".");
        specialStrings.add(" ");
        specialStrings.add("..");
        specialStrings.add("...");
        specialStrings.add("");
        for (String deviceName : WINDOWS_DEVICE_NAMES) {
            specialStrings.add(deviceName);
            specialStrings.add(deviceName.toLowerCase(Locale.ROOT));
            specialStrings.add(deviceName + ".txt");
            specialStrings.add(deviceName.toLowerCase(Locale.ROOT) + ".tar.gz");
            specialStrings.add(deviceName + ".");
            specialStrings.add(deviceName + " ");
            specialStrings.add(deviceName + " .txt");
            specialStrings.add(deviceName + ":");
        }

        int count = 0;
        for (String special : specialStrings) {
            for (String uri : List.of(
                    /* As a whole path segment, the last one and an inner one */
                    "https://h.example.com/" + special,
                    "https://h.example.com/" + special + "/b",
                    /* As a part of a path segment */
                    "https://h.example.com/a" + special + "b/c",
                    "https://h.example.com/a" + special,
                    "https://h.example.com/" + special + "a",
                    /* In the query string */
                    "https://h.example.com/a?" + special,
                    "https://h.example.com/a?b" + special + "c",
                    "https://h.example.com?" + special,
                    /* In the host name */
                    "https://" + special + ".example.com/a",
                    "https://h." + special + "/a",
                    "https://h" + special + ".example.com:8443/a")) {
                final CacheKey key = CacheKey.of(uri, Map.of("Accept", List.of("text/" + special, special)));
                if (key != null) {
                    assertPortable(key);
                    count++;
                }
            }
        }
        /* Most of the above URIs are expected to be accepted */
        Assertions.assertThat(count).isGreaterThan(specialStrings.size() * 8);
    }

    @Test
    void notPersistable() {
        Assertions.assertThat(CacheKey.of("https://user:password@h/a", null)).isNull();
        Assertions.assertThat(CacheKey.of("https://user@h/a", null)).isNull();
        Assertions.assertThat(CacheKey.of("ftp://h/a", null)).isNull();
        Assertions.assertThat(CacheKey.of("file:///antora/docs/modules/ROOT/pages/index.adoc", null)).isNull();
        Assertions.assertThat(CacheKey.of("mailto:joe@example.com", null)).isNull();
        Assertions.assertThat(CacheKey.of("https:///a", null)).isNull();
        Assertions.assertThat(CacheKey.of("https://h:port/a", null)).isNull();
        Assertions.assertThat(CacheKey.of("https://[::1/a", null)).isNull();
        Assertions.assertThat(CacheKey.of("https://../../x", null)).isNull();
        Assertions.assertThat(CacheKey.of("../relative.html", null)).isNull();
    }

    @Test
    void normalize() {
        Assertions.assertThat(CacheKey.normalize("https://h/a?b=c")).isEqualTo("https://h:443/a?b=c");
        Assertions.assertThat(CacheKey.normalize("https://h/a?b=c")).isNotEqualTo(CacheKey.normalize("https://h/a?b=d"));
        Assertions.assertThat(CacheKey.normalize("https://h/a")).isNotEqualTo(CacheKey.normalize("http://h/a"));
        Assertions.assertThat(CacheKey.normalize("https://h/a")).isNotEqualTo(CacheKey.normalize("https://h/A"));
        /* Different URIs having the same directory */
        Assertions.assertThat(path("https://h/a?b")).isEqualTo(path("https://h/a_b"));
        Assertions.assertThat(CacheKey.normalize("https://h/a?b")).isNotEqualTo(CacheKey.normalize("https://h/a_b"));
        Assertions.assertThat(CacheKey.normalize("https://h/a#")).isEqualTo(CacheKey.normalize("https://h/a"));
    }

    @Test
    void notLoopback() {
        for (String uri : List.of(
                "http://localhost/a",
                "http://localhost:8081/quarkus-antora/dev/index.html",
                "https://LOCALHOST:8443",
                "http://foo.localhost:8081/a",
                "http://localhost.:8081/a",
                "http://127.0.0.1:8081/a",
                "http://127.1.2.3/a",
                "http://[::1]:8081/a",
                "http://[0:0:0:0:0:0:0:1]/a",
                "http://0.0.0.0:8081/a",
                "http://user:password@localhost/a")) {
            Assertions.assertThat(LinkValidatorImpl.notLoopback().test(uri)).as(uri).isFalse();
        }
        for (String uri : List.of(
                "https://quarkus.io/guides",
                "http://128.0.0.1/a",
                "http://localhost.example.com/a",
                "http://notlocalhost/a",
                "http://[2001:db8::1]/a",
                "https://example.com/localhost",
                "https://example.com/?http://localhost/")) {
            Assertions.assertThat(LinkValidatorImpl.notLoopback().test(uri)).as(uri).isTrue();
        }
    }

    @Test
    void notUnder() {
        Assertions.assertThat(LinkValidatorImpl.notUnder(null).test("http://docs.example.com/a")).isTrue();
        for (String base : List.of(
                "http://docs.example.com:8081/quarkus-antora/dev/",
                "http://docs.example.com:8081/quarkus-antora/dev",
                "http://DOCS.example.com:8081/quarkus-antora/x/../dev/")) {
            for (String uri : List.of(
                    "http://docs.example.com:8081/quarkus-antora/dev/",
                    "http://docs.example.com:8081/quarkus-antora/dev",
                    "http://docs.example.com:8081/quarkus-antora/dev/index.html",
                    "http://docs.example.com:8081/quarkus-antora/dev/sub/page.html?foo=bar",
                    "http://docs.example.com:8081/quarkus-antora/other/../dev/index.html")) {
                Assertions.assertThat(LinkValidatorImpl.notUnder(java.net.URI.create(base)).test(uri))
                        .as(base + " " + uri).isFalse();
            }
            for (String uri : List.of(
                    "http://docs.example.com:8081/quarkus-antora/development/index.html",
                    "http://docs.example.com:8081/quarkus-antora/",
                    "http://docs.example.com:8082/quarkus-antora/dev/index.html",
                    "https://docs.example.com:8081/quarkus-antora/dev/index.html",
                    "http://example.com:8081/quarkus-antora/dev/index.html")) {
                Assertions.assertThat(LinkValidatorImpl.notUnder(java.net.URI.create(base)).test(uri))
                        .as(base + " " + uri).isTrue();
            }
        }
        /* Default port */
        Assertions.assertThat(LinkValidatorImpl.notUnder(java.net.URI.create("http://docs.example.com/"))
                .test("http://docs.example.com:80/a")).isFalse();
    }

    static String path(String uri) {
        return CacheKey.of(uri, null).relativePath();
    }

    static void assertPortable(CacheKey key) {
        key.acceptDirectory().forEach(CacheKeyTest::assertPortable);
        key.entryDirectory().forEach(CacheKeyTest::assertPortable);
    }

    /**
     * Asserts that the given {@code name} is a legal file name on Linux, macOS and Windows.
     */
    static void assertPortable(String name) {
        Assertions.assertThat(name).isNotEmpty().isNotEqualTo(".").isNotEqualTo("..");
        Assertions.assertThat(name.getBytes(StandardCharsets.UTF_8).length).as(name).isLessThanOrEqualTo(255);
        name.codePoints().forEach(cp -> {
            Assertions.assertThat(Character.isISOControl(cp)).as("Control character in '%s'", name).isFalse();
            Assertions.assertThat(REJECTED_CHARS.indexOf(cp)).as("Rejected character in '%s'", name).isEqualTo(-1);
            Assertions.assertThat(cp < Character.MIN_SURROGATE || cp > Character.MAX_SURROGATE)
                    .as("Unpaired surrogate in '%s'", name).isTrue();
        });
        Assertions.assertThat(name).doesNotEndWith(".").doesNotEndWith(" ");
        final int dot = name.indexOf('.');
        final String baseName = (dot >= 0 ? name.substring(0, dot) : name).stripTrailing().toUpperCase(Locale.ROOT);
        Assertions.assertThat(WINDOWS_DEVICE_NAMES).as("Device name '%s'", name).doesNotContain(baseName);
    }
}
