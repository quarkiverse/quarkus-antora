package io.quarkiverse.antorassured;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Clock;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoField;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;

import org.jboss.logging.Logger;
import org.jsoup.Connection.Method;
import org.jsoup.Connection.Response;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.select.Elements;
import org.jsoup.select.Selector.SelectorParseException;

/**
 * A validator of web links.
 *
 * @since 1.0.0
 */
public interface LinkValidator {

    public static LinkValidator defaultValidator() {
        return new LinkValidatorImpl();
    }

    /**
     * Checks whether the give URI is valid, typically by accessing the given HTTP resource, and returns the
     * {@link ValidationResult}.
     *
     * @param link the {@link Link} to check
     * @return the result of the validation
     *
     * @since 1.0.0
     */
    default ValidationResult validate(Link link) {
        return validate(link, 1);
    }

    /**
     * Checks whether the give URI is valid, typically by accessing the given HTTP resource, and returns the
     * {@link ValidationResult}.
     *
     * @param link the {@link Link} to check
     * @param attempt the attempt number, the first attempt is {@code 1}
     * @return the result of the validation
     *
     * @since 1.3.0
     */
    ValidationResult validate(Link link, int attempt);

    static class LinkValidatorImpl implements LinkValidator {
        private static final Logger log = Logger.getLogger(AntorAssured.class);
        private static final DateTimeFormatter HTTP_DATE_FORMAT = DateTimeFormatter
                .ofPattern("EEE, dd MMM yyyy HH:mm:ss z", Locale.ENGLISH).withZone(ZoneId.of("GMT"));
        private static final Pattern DIGITS_ONLY = Pattern.compile("[0-9]+");

        private static final Pattern JAVADOC_FRAGMENT_CHARS = Pattern.compile("[\\(\\)\\,\\.]");

        static final long RETRY_AFTER_DEFAULT = 10_000L;
        static final long RETRY_AFTER_MAX = 120_000L;

        /** JSoup documents by fragment-less URI */
        private final Map<String, CacheEntry> documents = new ConcurrentHashMap<>();
        /** Locks by fragment-less URI */
        private final Map<String, Lock> documentLocks = new HashMap<>();

        /** Error messages by URI that possibly have a fragment */
        private final Map<String, ValidationResult> errorMessages = new ConcurrentHashMap<>();
        /** Locks by URI, possibly with a fragment */
        private final Map<String, Lock> messageLocks = new HashMap<>();

        @Override
        public ValidationResult validate(Link link, int attempt) {
            final String uri = link.resolvedUri();

            log.debugf("Validating %s", uri);
            final Lock messageLock;
            synchronized (messageLocks) {
                messageLock = messageLocks.computeIfAbsent(uri, k -> new ReentrantLock());
            }

            messageLock.lock();
            try {
                final ValidationResult cached = errorMessages.get(uri);
                if (cached != null && (!cached.shouldRetry() || cached.attempt() >= attempt)) {
                    return cached;
                }

                String fragment = null;
                String fragmentLessUri = uri;
                int hashPos = uri.indexOf('#');
                if (hashPos >= 0) {
                    fragment = uri.substring(hashPos);
                    fragmentLessUri = uri.substring(0, hashPos);
                }

                final CacheEntry entry;

                final Lock fragmentLessLock;
                synchronized (documentLocks) {
                    fragmentLessLock = documentLocks.computeIfAbsent(uri, k -> new ReentrantLock());
                }
                fragmentLessLock.lock();
                try {
                    entry = documents.compute(fragmentLessUri, (k, v) -> {
                        if (v == null || (!v.isValid() && attempt > v.attempt)) {
                            return fetch(k, attempt);
                        } else {
                            return v;
                        }
                    });
                } finally {
                    fragmentLessLock.unlock();
                }

                if (!entry.isValid()) {
                    return logAndReturn(uri,
                            ValidationResult.retry(link, entry.message, entry.retryAtSystemTimeMs, entry.attempt));
                }

                /* No fragment */
                if (fragment == null || "#".equals(fragment)) {
                    return logAndReturn(uri, ValidationResult.valid(link));
                }

                if (fragment.startsWith("#L") && uri.startsWith("https://github.com/")) {
                    /* GitHub line fragments do not exist in the DOM - consider them valid */
                    return logAndReturn(uri, ValidationResult.valid(link));
                }

                /* Find the fragment */
                final Document doc = entry.document;

                if (JAVADOC_FRAGMENT_CHARS.matcher(fragment).find()) {
                    /* Those chars are illegal in CSS selectors, so Tagsoup will fail at parsing the selector */
                    final String id = fragment.substring(1);
                    if (doc.getElementById(id) != null) {
                        return logAndReturn(uri, ValidationResult.valid(link));
                    }
                }

                try {
                    Elements foundElems = doc.select(fragment);
                    if (foundElems.isEmpty()) {
                        foundElems = doc.select("a[name=\"" + fragment.substring(1) + "\"]");
                    }
                    if (!foundElems.isEmpty()) {
                        return logAndReturn(uri, ValidationResult.valid(link));
                    } else {
                        final ValidationResult result = ValidationResult.invalid(link, "Could not find " + fragment);
                        return logAndReturn(uri, result);
                    }
                } catch (SelectorParseException e) {
                    log.error("Bad fragment: " + fragment + " in URI " + link.originalUri(), e);
                    throw e;
                }
            } finally {
                messageLock.unlock();
            }

        }

        static CacheEntry fetch(final String uri, int attempt) {
            {
                try {
                    final Response resp = Jsoup
                            .connect(uri)
                            .method(Method.GET)
                            .ignoreHttpErrors(true)
                            .execute();
                    switch (resp.statusCode()) {
                        case 200:
                            return CacheEntry.valid(resp.parse());
                        case 301:
                        case 429:
                        case 500:
                        case 501:
                        case 502:
                        case 503:
                        case 504:
                            final String rawRetryAfter = resp.header("Retry-After");
                            final long retryAtSystemTimeMs = parseRetryAfter(rawRetryAfter, Clock.systemUTC());
                            return CacheEntry.retry(resp.statusCode(),
                                    resp.statusMessage() + " with Retry-After: " + rawRetryAfter, retryAtSystemTimeMs, attempt);
                        default:
                            return CacheEntry.invalid("" + resp.statusCode() + " " + resp.statusMessage());
                    }
                } catch (java.net.ConnectException e) {
                    return CacheEntry.invalid("Unable to connect: " + e.getMessage());
                } catch (java.net.UnknownHostException e) {
                    return CacheEntry.invalid("Unknown host " + e.getMessage());
                } catch (org.jsoup.HttpStatusException e) {
                    return CacheEntry.invalid("" + e.getStatusCode());
                } catch (Exception e) {
                    StringWriter sw = new StringWriter();
                    PrintWriter pw = new PrintWriter(sw);
                    e.printStackTrace(pw);
                    return CacheEntry.invalid(sw.toString());
                }
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

        record CacheEntry(String message, Document document, long retryAtSystemTimeMs, int attempt) {

            private static final int NO_RETRY = -1;
            static CacheEntry retry(int statusCode, String statusMessage, long retryAtSystemTimeMs, int attempt) {
                return new CacheEntry("" + statusCode + " " + statusMessage, null, retryAtSystemTimeMs, attempt);
            }

            static CacheEntry valid(Document document) {
                return new CacheEntry(null, document, NO_RETRY, NO_RETRY);
            }

            static CacheEntry invalid(String message) {
                return new CacheEntry(message, null, NO_RETRY, NO_RETRY);
            }

            public boolean isValid() {
                return document != null;
            }

            public boolean shouldRetry() {
                return retryAtSystemTimeMs != NO_RETRY;
            }
        }
    }
}
