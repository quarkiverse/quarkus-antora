package io.quarkiverse.antorassured;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import io.quarkiverse.antorassured.LinkValidator.LinkValidatorImpl;

/**
 * Validates a single URI concurrently through several {@link LinkValidatorImpl}s sharing a persistent cache directory.
 * Run by {@link PersistentCacheTest} both in the test JVM and in separate JVMs.
 */
public class PersistentCacheTestClient {

    /**
     * Exits with {@code 0} if all validations pass.
     *
     * @param args cache directory, URI, number of threads, a file to create when ready, a file to wait for before
     *        validating
     */
    public static void main(String[] args) throws Exception {
        final Path cacheDir = Path.of(args[0]);
        final String uri = args[1];
        final int threads = Integer.parseInt(args[2]);
        final Path readyFile = Path.of(args[3]);
        final Path goFile = Path.of(args[4]);

        final List<String> errors = validateConcurrently(cacheDir, uri, threads, () -> {
            Files.createFile(readyFile);
            final long deadline = System.currentTimeMillis() + 60_000L;
            while (!Files.exists(goFile)) {
                if (System.currentTimeMillis() > deadline) {
                    throw new IllegalStateException("Timed out waiting for " + goFile);
                }
                Thread.sleep(2);
            }
            return null;
        });
        errors.forEach(System.out::println);
        System.exit(errors.isEmpty() ? 0 : 1);
    }

    /**
     * @param cacheDir the root directory of the persistent cache
     * @param uri the URI to validate
     * @param threads the number of threads; each thread has its own {@link LinkValidatorImpl}
     * @param beforeStart called once all threads are ready; the threads start validating when it returns
     * @return the messages of the failed validations
     */
    static List<String> validateConcurrently(Path cacheDir, String uri, int threads, Callable<Void> beforeStart)
            throws Exception {
        final ExecutorService executor = Executors.newFixedThreadPool(threads);
        try {
            final java.util.concurrent.CountDownLatch ready = new java.util.concurrent.CountDownLatch(threads);
            final java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
            final List<Future<ValidationResult>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                final LinkValidator validator = new LinkValidatorImpl(
                        cacheDir,
                        LinkValidatorImpl.DEFAULT_CACHE_EXPIRATION,
                        LinkValidatorImpl.DEFAULT_CACHE_GC_AGE,
                        Duration.ofMillis(100),
                        3,
                        u -> true,
                        Clock.systemUTC());
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    go.await();
                    return validator.validate(
                            new ValidationRequest(Link.ofResolved(uri), 2, LinkStream.createDefaultGroup()));
                }));
            }
            if (!ready.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Threads not ready");
            }
            beforeStart.call();
            go.countDown();
            final List<String> errors = new ArrayList<>();
            for (Future<ValidationResult> future : futures) {
                final ValidationResult result = future.get(60, TimeUnit.SECONDS);
                if (result.isInvalid()) {
                    errors.add(result.toString());
                }
            }
            return errors;
        } finally {
            executor.shutdownNow();
        }
    }
}
