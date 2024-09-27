package io.quarkiverse.antora.test;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import org.jboss.logging.Logger;
import org.jsoup.Connection.Method;
import org.jsoup.Connection.Response;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.select.Elements;

/**
 * A validator of external links.
 */
public class LinkValidator {
    private static final Logger log = Logger.getLogger(AntoraTestUtils.class);

    /** JSoup documents by fragment-less URI */
    private final Map<String, CacheEntry> documents = new ConcurrentHashMap<>();
    /** Locks by fragment-less URI */
    private final Map<String, Lock> documentLocks = new HashMap<>();

    /** Error messages by URI that possibly have a fragment */
    private final Map<String, ValidationResult> errorMessages = new ConcurrentHashMap<>();
    /** Locks by URI, possibly with a fragment */
    private final Map<String, Lock> messageLocks = new HashMap<>();

    /**
     * @param uri
     * @return a {@link ValidationResult}
     */
    public ValidationResult validate(String uri) {

        final Lock messageLock;
        synchronized (messageLocks) {
            messageLock = messageLocks.computeIfAbsent(uri, k -> new ReentrantLock());
        }

        messageLock.lock();
        try {
            final ValidationResult cached = errorMessages.get(uri);
            if (cached != null) {
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
                entry = documents.computeIfAbsent(fragmentLessUri, k -> {
                    try {
                        final Response resp = Jsoup.connect(uri).method(Method.GET).execute();
                        if (resp.statusCode() != 200) {
                            return new CacheEntry("" + resp.statusCode() + "" + resp.statusMessage(), null);
                        }
                        return new CacheEntry(null, resp.parse());
                    } catch (java.net.UnknownHostException e) {
                        return new CacheEntry("Unknown host " + e.getMessage(), null);
                    } catch (org.jsoup.HttpStatusException e) {
                        return new CacheEntry("" + e.getStatusCode(), null);
                    } catch (Exception e) {
                        StringWriter sw = new StringWriter();
                        PrintWriter pw = new PrintWriter(sw);
                        e.printStackTrace(pw);
                        return new CacheEntry(sw.toString(), null);
                    }
                });
            } finally {
                fragmentLessLock.unlock();
            }

            if (!entry.isValid()) {
                return logAndReturn(uri, new ValidationResult(entry.message));
            }

            /* No fragment */
            if (fragment == null) {
                return logAndReturn(uri, ValidationResult.VALID);
            }

            if (fragment.startsWith("#L") && uri.startsWith("https://github.com/")) {
                /* GitHub line fragments do not exist in the DOM - consider them valid */
                return logAndReturn(uri, ValidationResult.VALID);
            }

            /* Find the fragment */
            final Document doc = entry.document;
            Elements foundElems = doc.select(fragment);
            if (foundElems.isEmpty()) {
                foundElems = doc.select("a[name=\"" + fragment.substring(1) + "\"]");
            }
            if (!foundElems.isEmpty()) {
                return logAndReturn(uri, ValidationResult.VALID);
            } else {
                final ValidationResult result = new ValidationResult("Could not find " + fragment);
                return logAndReturn(uri, result);
            }
        } finally {
            messageLock.unlock();
        }

    }

    private ValidationResult logAndReturn(String uri, ValidationResult result) {
        errorMessages.put(uri, result);
        if (result.isValid()) {
            log.debugf("    %s : %s", uri, result);
        } else {
            log.warnf("    %s : %s", uri, result);
        }
        return result;
    }

    public record CacheEntry(String message, Document document) {
        public boolean isValid() {
            return document != null;
        }
    }

    /**
     * A result of a link validation.
     */
    public record ValidationResult(String message) {
        public static final ValidationResult VALID = new ValidationResult(null);

        /**
         * @return {@code true} if the link validated is valid, {@code false} otherwise
         */
        boolean isValid() {
            return message == null;
        }

        /**
         * @throws IllegalStateException if this is an invalid result
         */
        public void assertValid() {
            if (message != null) {
                throw new IllegalStateException(message);
            }
        }

        /** {@inheritDoc} */
        public String toString() {
            return message == null ? "valid" : message;
        }
    }
}
