package io.quarkiverse.antorassured;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.charset.UnsupportedCharsetException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoField;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Predicate;
import java.util.regex.Pattern;

import org.jboss.logging.Logger;
import org.jsoup.Connection;
import org.jsoup.Connection.Method;
import org.jsoup.Jsoup;

/**
 * A validator of web links.
 *
 * @since 1.0.0
 */
public interface LinkValidator {

    /**
     * @return {@code new LinkValidatorImpl(new ArrayList<>(), 1)}
     *
     * @since 1.0.0
     */
    public static LinkValidator defaultValidator() {
        return new LinkValidatorImpl();
    }

    /**
     * Checks whether the give URI is valid, typically by accessing the given HTTP resource, and returns the
     * {@link ValidationResult}.
     *
     * @param request wraps the {@link Link} to check
     * @return the result of the validation
     *
     * @since 1.0.0
     */
    ValidationResult validate(ValidationRequest request);

    /**
     * Called by {@link LinkStream#validate(LinkValidator)} once, after the last {@link Link} of the {@link LinkStream}
     * was validated, unless no {@link Link} was passed to {@link #validate(ValidationRequest)} at all.
     * <p>
     * The default implementation does nothing. Implementations may clean up here.
     *
     * @since 4.0.0
     */
    default void validationFinished() {
    }

    /**
     * The default {@link LinkValidator}.
     * <p>
     * Responses having a {@code 2xx} status are stored in a persistent cache in the local file system, from where they are
     * served to subsequent validations, possibly running in another JVM, until they expire. The cache can be configured
     * only through
     * {@link #LinkValidatorImpl(Path, Duration, Duration, Duration, int, Predicate, Clock)}.
     */
    static class LinkValidatorImpl implements LinkValidator {
        private static final Logger log = Logger.getLogger(AntorAssured.class);
        private static final DateTimeFormatter HTTP_DATE_FORMAT = DateTimeFormatter
                .ofPattern("EEE, dd MMM yyyy HH:mm:ss z", Locale.ENGLISH).withZone(ZoneId.of("GMT"));
        private static final Pattern DIGITS_ONLY = Pattern.compile("[0-9]+");

        static final long RETRY_AFTER_DEFAULT = 60_000L;
        static final long RETRY_AFTER_MAX = 120_000L;

        /**
         * The default period for which the entries of the persistent cache are used: 36 hours
         *
         * @since 4.0.0
         */
        public static final Duration DEFAULT_CACHE_EXPIRATION = Duration.ofHours(36);
        /**
         * The default age after which the entries of the persistent cache are deleted: 180 days
         *
         * @since 4.0.0
         */
        public static final Duration DEFAULT_CACHE_GC_AGE = Duration.ofDays(180);
        /**
         * The default delay before trying again to lock an entry of the persistent cache that is locked by another class
         * loader of the current JVM: 1 second
         *
         * @since 4.0.0
         */
        public static final Duration DEFAULT_CACHE_LOCK_RETRY_DELAY = Duration.ofSeconds(1);
        /**
         * The default number of retries to lock an entry of the persistent cache that is locked by another class loader of
         * the current JVM: 3
         *
         * @since 4.0.0
         */
        public static final int DEFAULT_CACHE_LOCK_RETRIES = 3;
        private static final Pattern IP_V4_LITERAL = Pattern.compile("[0-9]{1,3}(\\.[0-9]{1,3}){3}");

        /* Package visible for tests */
        final Connection jsoupSession = Jsoup.newSession();

        /** JSoup documents by fragment-less URI */
        private final Map<String, CacheEntry> documents = new ConcurrentHashMap<>();
        /** Locks by fragment-less URI */
        private final Map<String, Lock> documentLocks = new HashMap<>();

        /** Error messages by URI that possibly have a fragment */
        private final Map<String, ValidationResult> errorMessages = new ConcurrentHashMap<>();
        /** Locks by URI, possibly with a fragment */
        private final Map<String, Lock> messageLocks = new HashMap<>();

        /** The second level cache behind {@link #documents} */
        private final PersistentCache persistentCache;
        /** Decides which fragment-less URIs may be looked up in and stored to {@link #persistentCache} */
        private final Predicate<String> cacheable;

        /**
         * Creates a new {@link LinkValidatorImpl} with a persistent cache in {@link #defaultCacheDirectory()} having the
         * default settings: {@link #DEFAULT_CACHE_EXPIRATION}, {@link #DEFAULT_CACHE_GC_AGE},
         * {@link #DEFAULT_CACHE_LOCK_RETRY_DELAY}, {@link #DEFAULT_CACHE_LOCK_RETRIES}, {@link #notLoopback()} and
         * {@link Clock#systemUTC()}.
         */
        public LinkValidatorImpl() {
            this(
                    defaultCacheDirectory(),
                    DEFAULT_CACHE_EXPIRATION,
                    DEFAULT_CACHE_GC_AGE,
                    DEFAULT_CACHE_LOCK_RETRY_DELAY,
                    DEFAULT_CACHE_LOCK_RETRIES,
                    notLoopback(),
                    Clock.systemUTC());
        }

        /**
         * Creates a new {@link LinkValidatorImpl} with a custom configuration of the persistent cache.
         *
         * @param cacheDirectory the root directory of the persistent cache; it is created when the first response is
         *        about to be looked up; see {@link #defaultCacheDirectory()}
         * @param cacheExpiration cache entries older than this are ignored and the page is fetched again; see
         *        {@link #DEFAULT_CACHE_EXPIRATION}
         * @param cacheGcAge cache entries older than this are deleted from {@code cacheDirectory} in
         *        {@link #validationFinished()}; see {@link #DEFAULT_CACHE_GC_AGE}
         * @param cacheLockRetryDelay how long to wait before trying again to lock a cache entry that is locked by another
         *        class loader of the current JVM; see {@link #DEFAULT_CACHE_LOCK_RETRY_DELAY}
         * @param cacheLockRetries how many times to try again to lock a cache entry that is locked by another class
         *        loader of the current JVM, before validating the URI without the cache; see
         *        {@link #DEFAULT_CACHE_LOCK_RETRIES}
         * @param cacheable gets the absolute fragment-less URI that is about to be validated and returns {@code true} if
         *        the persistent cache may be used for it or {@code false} if the URI has to be fetched always and its
         *        response may not be stored; a {@link Predicate} returning {@code false} for every URI switches the
         *        persistent cache off; see {@link #notLoopback()}
         * @param clock the source of the current time for the persistent cache
         *
         * @since 4.0.0
         */
        public LinkValidatorImpl(
                Path cacheDirectory,
                Duration cacheExpiration,
                Duration cacheGcAge,
                Duration cacheLockRetryDelay,
                int cacheLockRetries,
                Predicate<String> cacheable,
                Clock clock) {
            Objects.requireNonNull(cacheDirectory, "cacheDirectory");
            Objects.requireNonNull(cacheExpiration, "cacheExpiration");
            Objects.requireNonNull(cacheGcAge, "cacheGcAge");
            Objects.requireNonNull(cacheLockRetryDelay, "cacheLockRetryDelay");
            if (cacheLockRetries < 0) {
                throw new IllegalArgumentException("cacheLockRetries must not be negative; found " + cacheLockRetries);
            }
            this.cacheable = Objects.requireNonNull(cacheable, "cacheable");
            this.persistentCache = new PersistentCache(
                    cacheDirectory,
                    cacheExpiration,
                    cacheGcAge,
                    cacheLockRetryDelay,
                    cacheLockRetries,
                    Objects.requireNonNull(clock, "clock"));
        }

        /**
         * @return {@code .cache/antorassured/pages} under the directory the {@code user.home} system property points at
         *
         * @since 4.0.0
         */
        public static Path defaultCacheDirectory() {
            return Path.of(System.getProperty("user.home"), ".cache", "antorassured", "pages");
        }

        /**
         * @return a {@link Predicate} returning {@code false} for {@code http} and {@code https} URIs whose host is
         *         {@code localhost}, a subdomain of {@code localhost}, a loopback IP address, such as {@code 127.0.0.1} or
         *         {@code [::1]}, or the wildcard IP address, such as {@code 0.0.0.0}; and returning {@code true} for other
         *         URIs. No host name is resolved to find that out.
         *
         * @since 4.0.0
         */
        public static Predicate<String> notLoopback() {
            return uri -> {
                final CacheKey.ParsedUri parsed = CacheKey.ParsedUri.parse(uri);
                if (parsed == null) {
                    return true;
                }
                /* A fully qualified host name may end with a dot */
                final String host = parsed.host().endsWith(".")
                        ? parsed.host().substring(0, parsed.host().length() - 1)
                        : parsed.host();
                if (host.equals("localhost") || host.endsWith(".localhost")) {
                    return false;
                }
                if (IP_V4_LITERAL.matcher(host).matches()) {
                    return !host.startsWith("127.") && !host.equals("0.0.0.0");
                }
                if (host.startsWith("[")) {
                    try {
                        /* No name service is involved for IPv6 literals */
                        final InetAddress address = InetAddress.getByName(host);
                        return !address.isLoopbackAddress() && !address.isAnyLocalAddress();
                    } catch (UnknownHostException | RuntimeException e) {
                        return false;
                    }
                }
                return true;
            };
        }

        /**
         * @param baseUri the base URI of the site under test; can be {@code null}
         * @return a {@link Predicate} returning {@code false} for the given {@code baseUri} and for URIs under it; and
         *         returning {@code true} for other URIs
         */
        static Predicate<String> notUnder(URI baseUri) {
            final CacheKey.ParsedUri base = baseUri == null ? null : CacheKey.ParsedUri.parse(baseUri.toString());
            if (base == null) {
                return uri -> true;
            }
            final String prefix = base.normalizedWithoutQuery();
            final String prefixWithSlash = prefix + "/";
            return uri -> {
                final CacheKey.ParsedUri parsed = CacheKey.ParsedUri.parse(uri);
                if (parsed == null) {
                    return true;
                }
                final String normalized = parsed.normalizedWithoutQuery();
                return !normalized.equals(prefix) && !normalized.startsWith(prefixWithSlash);
            };
        }

        /**
         * @param baseUri the base URI of the site under test; can be {@code null}
         * @return a new {@link LinkValidatorImpl} with the default settings, that in addition to loopback URIs, does not
         *         use the persistent cache for URIs under the given {@code baseUri}
         */
        static LinkValidatorImpl forSite(URI baseUri) {
            return new LinkValidatorImpl(
                    defaultCacheDirectory(),
                    DEFAULT_CACHE_EXPIRATION,
                    DEFAULT_CACHE_GC_AGE,
                    DEFAULT_CACHE_LOCK_RETRY_DELAY,
                    DEFAULT_CACHE_LOCK_RETRIES,
                    notLoopback().and(notUnder(baseUri)),
                    Clock.systemUTC());
        }

        PersistentCache persistentCache() {
            return persistentCache;
        }

        /**
         * Deletes the entries of the persistent cache that are older than the garbage collection age, provided the
         * persistent cache was used for at least one URI by this {@link LinkValidatorImpl}.
         *
         * @since 4.0.0
         */
        @Override
        public void validationFinished() {
            persistentCache.collectGarbage();
        }

        @Override
        public ValidationResult validate(ValidationRequest req) {
            final Link link = req.link();
            final String uri = link.resolvedUri();

            log.debugf("Validating %s", uri);
            final Lock messageLock;
            synchronized (messageLocks) {
                messageLock = messageLocks.computeIfAbsent(uri, k -> new ReentrantLock());
            }

            messageLock.lock();
            try {
                final ValidationResult cached = errorMessages.get(uri);
                if (cached != null && !cached.shouldRetry()) {
                    return cached;
                }

                final String fragmentLessUri = link.resolvedFragmentlessUri();

                final CacheEntry entry;

                final Lock fragmentLessLock;
                synchronized (documentLocks) {
                    fragmentLessLock = documentLocks.computeIfAbsent(fragmentLessUri, k -> new ReentrantLock());
                }
                fragmentLessLock.lock();
                try {
                    final CacheEntry oldEntry = documents.get(fragmentLessUri);
                    if (oldEntry != null && !oldEntry.shouldRetry()) {
                        entry = oldEntry;
                    } else {
                        final LinkGroup group = req.group();
                        final CacheKey cacheKey = cacheKey(fragmentLessUri, group.headers());
                        /* The lock makes other validators wait for the entry we are possibly going to store */
                        final PersistentCache.EntryLock cacheLock = cacheKey == null ? null : persistentCache.lock(cacheKey);
                        try {
                            final Response stored = cacheLock == null ? null : cacheLock.read();
                            if (stored != null) {
                                entry = CacheEntry.valid(stored);
                            } else {
                                final long delay = group.rateLimit().scheduleInMilliseconds(group.pattern().pattern());
                                if (delay > 0L) {
                                    return logAndReturn(uri,
                                            ValidationResult.retry(link, -429, "Delayed " + delay + " ms due to a rate limit",
                                                    System.currentTimeMillis() + delay, 0, Integer.MAX_VALUE));
                                }
                                entry = fetch(jsoupSession, fragmentLessUri, group.headers(),
                                        oldEntry == null ? 1 : (oldEntry.attempt + 1));
                                if (cacheLock != null && entry.isValid() && entry.response.body() != null) {
                                    cacheLock.write(entry.response);
                                }
                            }
                            documents.put(fragmentLessUri, entry);
                        } finally {
                            if (cacheLock != null) {
                                cacheLock.close();
                            }
                        }
                    }
                } finally {
                    fragmentLessLock.unlock();
                }

                req.group().stats().recordStatus(entry.response.statusCode());

                if (!entry.isValid()) {
                    return logAndReturn(uri,
                            ValidationResult.retry(link, entry.response.statusCode(), entry.message, entry.retryAtSystemTimeMs,
                                    entry.attempt, req.maxAttempts()));
                }

                return logAndReturn(uri, req.group().fragmentValidator().validate(link, entry.response));

            } finally {
                messageLock.unlock();
            }

        }

        /**
         * @param fragmentLessUri the URI to validate
         * @param headers the request headers of the {@link LinkGroup} the URI belongs to
         * @return the key for looking up the given {@code fragmentLessUri} in {@link #persistentCache} or {@code null} if
         *         the {@link #persistentCache} should not be used
         */
        private CacheKey cacheKey(String fragmentLessUri, Map<String, List<String>> headers) {
            try {
                return cacheable.test(fragmentLessUri) ? CacheKey.of(fragmentLessUri, headers) : null;
            } catch (RuntimeException e) {
                log.warnf("Not caching %s: %s", fragmentLessUri, e.toString());
                return null;
            }
        }

        static CacheEntry fetch(
                final Connection jsoupSession,
                final String fragmentlessUri,
                Map<String, List<String>> headers,
                int attempt) {
            {
                try {

                    final Connection req = jsoupSession
                            .newRequest(fragmentlessUri)
                            .ignoreContentType(true)
                            .method(Method.GET)
                            .ignoreHttpErrors(true);
                    if (headers != null) {
                        for (Entry<String, List<String>> header : headers.entrySet()) {
                            for (String val : header.getValue()) {
                                req.header(header.getKey(), val);
                            }
                        }
                    }
                    final org.jsoup.Connection.Response resp = req.execute();
                    final int statusCode = resp.statusCode();
                    log.debugf("Fetched %d: %s", statusCode, fragmentlessUri);
                    final Response response = new Response(
                            fragmentlessUri,
                            statusCode,
                            charset(resp.charset()),
                            resp.contentType(),
                            resp.bodyAsBytes());
                    switch (statusCode) {
                        case 200:
                            return CacheEntry.valid(response);
                        case 301:
                        case 429:
                        case 500:
                        case 501:
                        case 502:
                        case 503:
                        case 504:
                            final String rawRetryAfter = resp.header("Retry-After");
                            final long retryAtSystemTimeMs = parseRetryAfter(rawRetryAfter, Clock.systemUTC());
                            return CacheEntry.retry(
                                    response,
                                    statusCode + ", Retry-After: " + rawRetryAfter,
                                    retryAtSystemTimeMs, attempt);
                        default:
                            return CacheEntry.invalid(response, "" + statusCode, attempt);
                    }
                } catch (java.net.ConnectException e) {
                    return CacheEntry.invalid(Response.none(fragmentlessUri),
                            e.getMessage() != null ? "Unable to connect: " + e.getMessage() : "Unable to connect");
                } catch (java.net.UnknownHostException e) {
                    return CacheEntry.invalid(Response.none(fragmentlessUri), "Unknown host " + e.getMessage());
                } catch (org.jsoup.HttpStatusException e) {
                    return CacheEntry.invalid(new Response(fragmentlessUri, e.getStatusCode(), null, null, null),
                            "" + e.getStatusCode());
                } catch (Exception e) {
                    StringWriter sw = new StringWriter();
                    PrintWriter pw = new PrintWriter(sw);
                    e.printStackTrace(pw);
                    return CacheEntry.invalid(Response.none(fragmentlessUri), sw.toString());
                }
            }
        }

        static Charset charset(String charset) {
            if (charset == null) {
                return StandardCharsets.UTF_8;
            }
            try {
                return Charset.forName(charset);
            } catch (UnsupportedCharsetException e) {
                return StandardCharsets.UTF_8;
            }
        }

        static long parseRetryAfter(String rawRetryAfter, Clock clock) {
            if (rawRetryAfter != null) {
                if (DIGITS_ONLY.matcher(rawRetryAfter).matches()) {
                    final long seconds = Long.parseLong(rawRetryAfter);
                    return clock.millis() + Math.min(seconds * 1000, RETRY_AFTER_MAX);
                } else {
                    try {
                        return Math.min(
                                HTTP_DATE_FORMAT.parse(rawRetryAfter).getLong(ChronoField.INSTANT_SECONDS) * 1000,
                                clock.millis() + RETRY_AFTER_MAX);
                    } catch (DateTimeParseException e) {
                        log.warnf(e, "Could not parse Retry-After: %s from fragmentLessUri", rawRetryAfter);
                        return clock.millis() + RETRY_AFTER_DEFAULT;
                    }
                }
            }
            return clock.millis() + RETRY_AFTER_DEFAULT;
        }

        private ValidationResult logAndReturn(String uri, ValidationResult result) {
            errorMessages.put(uri, result);
            if (result.isValid()) {
                log.debugf("    %s", result);
            } else {
                log.warnf("    %s", result);
            }
            return result;
        }

        record CacheEntry(String message, Response response, long retryAtSystemTimeMs, int attempt) {

            private static final int NO_RETRY = -1;

            static CacheEntry retry(Response response, String message, long retryAtSystemTimeMs, int attempt) {
                return new CacheEntry(message, response, retryAtSystemTimeMs, attempt);
            }

            static CacheEntry valid(Response response) {
                return new CacheEntry(null, response, NO_RETRY, NO_RETRY);
            }

            static CacheEntry invalid(Response response, String message, int attemptCount) {
                return new CacheEntry(message, response, NO_RETRY, attemptCount);
            }

            static CacheEntry invalid(Response response, String message) {
                return new CacheEntry(message, response, NO_RETRY, NO_RETRY);
            }

            public boolean isValid() {
                return 200 <= response.statusCode() && response.statusCode() < 300;
            }

            public boolean shouldRetry() {
                return retryAtSystemTimeMs != NO_RETRY;
            }
        }
    }
}
