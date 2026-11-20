package io.quarkiverse.antorassured;

import java.nio.charset.StandardCharsets;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

/**
 * The key of an entry of the {@link PersistentCache}: a fragment-less {@code http} or {@code https} URI and the value of the
 * {@code Accept} header it is requested with.
 * <p>
 * The key maps to a directory relative to the cache root:
 * {@code <reversed host>/<scheme>/<port>/<accept>/<path segments>[?query]}, where each name is sanitized so that it is
 * accepted by Linux, macOS and Windows. The mapping is lossy: several URIs may map to the same directory. That is why
 * {@link #normalizedUri()} has to be compared with the URI stored in the entry.
 *
 * @param uri the fragment-less URI as it was requested
 * @param normalizedUri the {@code uri} with lowercased scheme and host, explicit port, dot segments removed and without
 *        trailing slash
 * @param acceptDirectory the names of the directories leading from the cache root to the {@code <accept>} directory
 * @param entryDirectory the names of the directories leading from the {@code <accept>} directory to the entry directory;
 *        empty for the root path without query
 *
 * @since 4.0.0
 */
record CacheKey(String uri, String normalizedUri, List<String> acceptDirectory, List<String> entryDirectory) {

    /** The name of the {@code <accept>} directory for requests without {@code Accept} header */
    static final String NO_ACCEPT = "no-accept";
    /** The longest file name in bytes accepted by the common file systems */
    static final int MAX_NAME_BYTES = 255;
    private static final String HASH_DELIMITER = "---";
    private static final int HASH_LENGTH = 16;
    /** Names reserved by Windows, with any extension and in any letter case */
    private static final Set<String> WINDOWS_DEVICE_NAMES = Set.of(
            "CON", "PRN", "AUX", "NUL", "CONIN$", "CONOUT$",
            "COM0", "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9", "COM¹", "COM²",
            "COM³",
            "LPT0", "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9", "LPT¹", "LPT²",
            "LPT³");

    /**
     * @param uri an absolute fragment-less URI
     * @param headers the request headers out of which only {@code Accept} is taken into account
     * @return a new {@link CacheKey} or {@code null} if a response to the given request may not be stored, because the
     *         {@code uri} is not a {@code http} or {@code https} URI, cannot be parsed or contains user info
     */
    static CacheKey of(String uri, Map<String, List<String>> headers) {
        final ParsedUri parsed = ParsedUri.parse(uri);
        if (parsed == null || parsed.userInfo() != null) {
            return null;
        }

        final List<String> acceptDirectory = new ArrayList<>();
        if (parsed.host().startsWith("[")) {
            /* IPv6 literal */
            acceptDirectory.add(sanitize(parsed.host()));
        } else {
            final String[] labels = parsed.host().split("\\.");
            for (int i = labels.length - 1; i >= 0; i--) {
                if (!labels[i].isEmpty()) {
                    acceptDirectory.add(sanitize(labels[i]));
                }
            }
            if (acceptDirectory.isEmpty()) {
                return null;
            }
        }
        acceptDirectory.add(parsed.scheme());
        acceptDirectory.add(String.valueOf(parsed.port()));
        acceptDirectory.add(sanitize(accept(headers)));

        final List<String> entryDirectory = new ArrayList<>();
        final String path = parsed.path();
        if (!path.isEmpty()) {
            /* path starts with a slash */
            for (String segment : path.substring(1).split("/", -1)) {
                entryDirectory.add(segment);
            }
        }
        if (parsed.query() != null) {
            final String query = "?" + parsed.query();
            if (entryDirectory.isEmpty()) {
                entryDirectory.add(query);
            } else {
                final int last = entryDirectory.size() - 1;
                entryDirectory.set(last, entryDirectory.get(last) + query);
            }
        }
        entryDirectory.replaceAll(CacheKey::sanitize);

        return new CacheKey(
                uri,
                parsed.normalized(),
                Collections.unmodifiableList(acceptDirectory),
                Collections.unmodifiableList(entryDirectory));
    }

    /**
     * @param uri an absolute URI
     * @return the given {@code uri} without fragment, with lowercased scheme and host, explicit port, dot segments removed
     *         and without trailing slash, or {@code null} if the {@code uri} is not a {@code http} or {@code https} URI or if
     *         it cannot be parsed
     */
    static String normalize(String uri) {
        final ParsedUri parsed = ParsedUri.parse(uri);
        return parsed == null ? null : parsed.normalized();
    }

    /**
     * @param root the root directory of the cache
     * @return the absolute directory of the entry
     * @throws IllegalStateException if the directory would be outside of its {@code <accept>} directory or outside of the
     *         given {@code root}
     * @throws InvalidPathException if the current file system rejects some name in spite of {@link #sanitize(String)}
     */
    Path resolve(Path root) {
        final Path normalizedRoot = root.toAbsolutePath().normalize();
        Path acceptDir = normalizedRoot;
        for (String name : acceptDirectory) {
            acceptDir = acceptDir.resolve(name);
        }
        Path entryDir = acceptDir;
        for (String name : entryDirectory) {
            entryDir = entryDir.resolve(name);
        }
        acceptDir = acceptDir.normalize();
        entryDir = entryDir.normalize();
        if (acceptDir.equals(normalizedRoot) || !acceptDir.startsWith(normalizedRoot) || !entryDir.startsWith(acceptDir)) {
            throw new IllegalStateException("Cache entry directory " + entryDir + " of " + uri + " is outside of " + acceptDir);
        }
        return entryDir;
    }

    /**
     * @return the directory of the entry relative to the cache root, with {@code /} as the delimiter
     */
    String relativePath() {
        final StringBuilder result = new StringBuilder();
        for (String name : acceptDirectory) {
            result.append(name).append('/');
        }
        for (String name : entryDirectory) {
            result.append(name).append('/');
        }
        result.setLength(result.length() - 1);
        return result.toString();
    }

    /**
     * @param headers the request headers
     * @return the values of the {@code Accept} header joined by comma or {@value #NO_ACCEPT} if there is no such header in
     *         the given {@code headers}
     */
    static String accept(Map<String, List<String>> headers) {
        if (headers != null) {
            StringBuilder result = null;
            for (Entry<String, List<String>> header : headers.entrySet()) {
                if ("Accept".equalsIgnoreCase(header.getKey()) && header.getValue() != null) {
                    for (String value : header.getValue()) {
                        if (result == null) {
                            result = new StringBuilder();
                        } else {
                            result.append(',');
                        }
                        result.append(value);
                    }
                }
            }
            if (result != null) {
                return result.toString();
            }
        }
        return NO_ACCEPT;
    }

    /**
     * Makes the given {@code name} usable as a file or directory name on Linux, macOS and Windows. The result is the same
     * on every platform.
     *
     * @param name a single URI path segment, host name label or any other string that should become a single file name
     * @return the sanitized name, never empty and never longer than {@value #MAX_NAME_BYTES} bytes in UTF-8
     */
    static String sanitize(String name) {
        final StringBuilder result = new StringBuilder(name.length() + 1);
        name.codePoints().forEach(cp -> result.appendCodePoint(isRejected(cp) ? '_' : cp));

        if (result.isEmpty()) {
            result.append('_');
        } else {
            final int last = result.length() - 1;
            if (result.charAt(last) == '.' || result.charAt(last) == ' ') {
                result.setCharAt(last, '_');
            }
        }

        /* Windows reserves device names with any extension, so the underscore has to precede the extension */
        final int dot = result.indexOf(".");
        int deviceNameEnd = dot >= 0 ? dot : result.length();
        while (deviceNameEnd > 0 && result.charAt(deviceNameEnd - 1) == ' ') {
            deviceNameEnd--;
        }
        if (WINDOWS_DEVICE_NAMES.contains(result.substring(0, deviceNameEnd).toUpperCase(Locale.ROOT))) {
            result.insert(deviceNameEnd, '_');
        }

        return shorten(result.toString());
    }

    private static boolean isRejected(int codePoint) {
        if (Character.isISOControl(codePoint)
                || (Character.MIN_SURROGATE <= codePoint && codePoint <= Character.MAX_SURROGATE)) {
            /* A code point from the surrogate range is an unpaired surrogate that cannot be encoded */
            return true;
        }
        switch (codePoint) {
            case '<':
            case '>':
            case ':':
            case '"':
            case '/':
            case '\\':
            case '|':
            case '?':
            case '*':
                return true;
            default:
                return false;
        }
    }

    private static String shorten(String name) {
        final byte[] bytes = name.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= MAX_NAME_BYTES) {
            return name;
        }
        final String hash;
        try {
            hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)).substring(0, HASH_LENGTH);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        final int maxPrefixBytes = MAX_NAME_BYTES - HASH_DELIMITER.length() - HASH_LENGTH;
        final StringBuilder result = new StringBuilder();
        int prefixBytes = 0;
        for (int i = 0; i < name.length();) {
            final int cp = name.codePointAt(i);
            final int cpBytes = cp < 0x80 ? 1 : cp < 0x800 ? 2 : cp < 0x10000 ? 3 : 4;
            if (prefixBytes + cpBytes > maxPrefixBytes) {
                break;
            }
            result.appendCodePoint(cp);
            prefixBytes += cpBytes;
            i += Character.charCount(cp);
        }
        return result.append(HASH_DELIMITER).append(hash).toString();
    }

    /**
     * A leniently parsed {@code http} or {@code https} URI.
     *
     * @param scheme lowercased {@code http} or {@code https}
     * @param userInfo the part of the authority before {@code @} or {@code null} if there is none
     * @param host lowercased host name or IP literal; IPv6 literals include the square brackets
     * @param port the explicit port or the default port of the {@code scheme}
     * @param path the path with dot segments removed and without trailing slash; an empty string for the root path
     * @param query the query string without the leading question mark or {@code null} if there is none
     *
     * @since 4.0.0
     */
    record ParsedUri(String scheme, String userInfo, String host, int port, String path, String query) {

        /**
         * @param uri the URI to parse
         * @return a new {@link ParsedUri} or {@code null} if the given {@code uri} is not an absolute {@code http} or
         *         {@code https} URI or if its authority cannot be parsed
         */
        static ParsedUri parse(String uri) {
            if (uri == null) {
                return null;
            }
            final int schemeEnd = uri.indexOf("://");
            if (schemeEnd <= 0) {
                return null;
            }
            final String scheme = uri.substring(0, schemeEnd).toLowerCase(Locale.ROOT);
            final int defaultPort;
            if ("http".equals(scheme)) {
                defaultPort = 80;
            } else if ("https".equals(scheme)) {
                defaultPort = 443;
            } else {
                return null;
            }

            final int hash = uri.indexOf('#', schemeEnd + 3);
            final String rest = hash >= 0 ? uri.substring(schemeEnd + 3, hash) : uri.substring(schemeEnd + 3);
            int authorityEnd = 0;
            while (authorityEnd < rest.length() && rest.charAt(authorityEnd) != '/' && rest.charAt(authorityEnd) != '?') {
                authorityEnd++;
            }
            final String authority = rest.substring(0, authorityEnd);
            final String pathAndQuery = rest.substring(authorityEnd);

            final int at = authority.lastIndexOf('@');
            final String userInfo = at >= 0 ? authority.substring(0, at) : null;
            final String hostAndPort = authority.substring(at + 1);
            final String host;
            final String rawPort;
            if (hostAndPort.startsWith("[")) {
                final int bracket = hostAndPort.indexOf(']');
                if (bracket < 0) {
                    return null;
                }
                host = hostAndPort.substring(0, bracket + 1);
                rawPort = hostAndPort.substring(bracket + 1);
            } else {
                final int colon = hostAndPort.lastIndexOf(':');
                host = colon >= 0 ? hostAndPort.substring(0, colon) : hostAndPort;
                rawPort = colon >= 0 ? hostAndPort.substring(colon) : "";
            }
            if (host.isEmpty()) {
                return null;
            }
            final int port;
            if (rawPort.isEmpty() || rawPort.equals(":")) {
                port = defaultPort;
            } else if (rawPort.matches(":[0-9]{1,5}")) {
                port = Integer.parseInt(rawPort.substring(1));
            } else {
                return null;
            }

            final int queryStart = pathAndQuery.indexOf('?');
            final String rawPath = queryStart >= 0 ? pathAndQuery.substring(0, queryStart) : pathAndQuery;
            final String query = queryStart >= 0 ? pathAndQuery.substring(queryStart + 1) : null;
            String path = removeDotSegments(rawPath);
            if (path.endsWith("/")) {
                path = path.substring(0, path.length() - 1);
            }
            return new ParsedUri(scheme, userInfo, host.toLowerCase(Locale.ROOT), port, path, query);
        }

        /**
         * @return this URI without user info and with an explicit port
         */
        String normalized() {
            final String result = normalizedWithoutQuery();
            return query == null ? result : result + "?" + query;
        }

        /**
         * @return this URI without user info and query, with an explicit port
         */
        String normalizedWithoutQuery() {
            return scheme + "://" + host + ":" + port + path;
        }

        /**
         * Removes the dot segments as described in
         * <a href="https://www.rfc-editor.org/rfc/rfc3986#section-5.2.4">RFC 3986, section 5.2.4</a>.
         *
         * @param path an empty path or a path starting with slash
         * @return the given {@code path} without any {@code .} and {@code ..} segments
         */
        static String removeDotSegments(String path) {
            final StringBuilder out = new StringBuilder(path.length());
            int i = 0;
            while (i < path.length()) {
                if (path.startsWith("../", i)) {
                    i += 3;
                } else if (path.startsWith("./", i)) {
                    i += 2;
                } else if (path.startsWith("/./", i)) {
                    i += 2;
                } else if (path.startsWith("/.", i) && i + 2 == path.length()) {
                    out.append('/');
                    i += 2;
                } else if (path.startsWith("/../", i)) {
                    i += 3;
                    removeLastSegment(out);
                } else if (path.startsWith("/..", i) && i + 3 == path.length()) {
                    removeLastSegment(out);
                    out.append('/');
                    i += 3;
                } else if ((path.startsWith(".", i) && i + 1 == path.length())
                        || (path.startsWith("..", i) && i + 2 == path.length())) {
                    i = path.length();
                } else {
                    int segmentEnd = path.indexOf('/', i + 1);
                    if (segmentEnd < 0) {
                        segmentEnd = path.length();
                    }
                    out.append(path, i, segmentEnd);
                    i = segmentEnd;
                }
            }
            return out.toString();
        }

        private static void removeLastSegment(StringBuilder out) {
            final int lastSlash = out.lastIndexOf("/");
            out.setLength(lastSlash < 0 ? 0 : lastSlash);
        }
    }
}
