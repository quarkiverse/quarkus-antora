package io.quarkiverse.antorassured;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.charset.UnsupportedCharsetException;
import java.time.Clock;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoField;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
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

    static class LinkValidatorImpl implements LinkValidator {
        private static final Logger log = Logger.getLogger(AntorAssured.class);
        private static final DateTimeFormatter HTTP_DATE_FORMAT = DateTimeFormatter
                .ofPattern("EEE, dd MMM yyyy HH:mm:ss z", Locale.ENGLISH).withZone(ZoneId.of("GMT"));
        private static final Pattern DIGITS_ONLY = Pattern.compile("[0-9]+");

        static final long RETRY_AFTER_DEFAULT = 60_000L;
        static final long RETRY_AFTER_MAX = 120_000L;

        private final Connection jsoupSession = Jsoup.newSession();

        /** JSoup documents by fragment-less URI */
        private final Map<String, CacheEntry> documents = new ConcurrentHashMap<>();
        /** Locks by fragment-less URI */
        private final Map<String, Lock> documentLocks = new HashMap<>();

        /** Error messages by URI that possibly have a fragment */
        private final Map<String, ValidationResult> errorMessages = new ConcurrentHashMap<>();
        /** Locks by URI, possibly with a fragment */
        private final Map<String, Lock> messageLocks = new HashMap<>();

        public LinkValidatorImpl() {
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
                    final LinkGroup group = req.group();
                    final long delay = group.rateLimit().scheduleInMilliseconds(group.pattern().pattern());
                    if (delay > 0L) {
                        return logAndReturn(uri,
                                ValidationResult.retry(link, -429, "Delayed " + delay + " ms due to a rate limit",
                                        System.currentTimeMillis() + delay, 0, Integer.MAX_VALUE));
                    }
                    entry = documents.compute(fragmentLessUri, (k, v) -> {
                        if (v == null || v.shouldRetry()) {
                            return fetch(jsoupSession, k, group.headers(), v == null ? 1 : (v.attempt + 1));
                        } else {
                            return v;
                        }
                    });
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
