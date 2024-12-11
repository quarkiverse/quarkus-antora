package io.quarkiverse.antorassured;

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
    ValidationResult validate(Link link);

    static class LinkValidatorImpl implements LinkValidator {
        private static final Logger log = Logger.getLogger(AntorAssured.class);

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
        @Override
        public ValidationResult validate(Link link) {
            final String uri = link.resolvedUri();

            log.debugf("Validating %s", uri);
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
                        } catch (java.net.ConnectException e) {
                            return new CacheEntry("Unable to connect: " + e.getMessage(), null);
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
                    return logAndReturn(uri, ValidationResult.invalid(link, entry.message));
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
                    log.error("Bad fragment: fragment", e);
                    throw e;
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

        record CacheEntry(String message, Document document) {
            public boolean isValid() {
                return document != null;
            }
        }
    }
}
