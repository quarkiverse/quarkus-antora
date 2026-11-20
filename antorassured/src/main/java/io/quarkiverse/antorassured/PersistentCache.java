package io.quarkiverse.antorassured;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.locks.ReentrantLock;

import org.jboss.logging.Logger;

/**
 * A store of HTTP responses in the local file system that outlives the current JVM.
 * <p>
 * Each entry lives in a directory of its own, see {@link CacheKey}, that contains {@value #METADATA_FILE},
 * {@value #BODY_FILE} and {@value #LOCK_FILE}. An entry may be accessed only while holding its {@link EntryLock}.
 * <p>
 * No method of this class throws an exception because of the state of the file system. A cache that cannot be read,
 * written or locked behaves like an empty one.
 *
 * @since 4.0.0
 */
final class PersistentCache {
    private static final Logger log = Logger.getLogger(AntorAssured.class);

    static final String METADATA_FILE = "response---antorassured.properties";
    static final String BODY_FILE = "body---antorassured.txt";
    static final String LOCK_FILE = "entry---antorassured.lock";
    private static final String TMP_SUFFIX = ".tmp";

    private static final String URI = "uri";
    private static final String STATUS_CODE = "statusCode";
    private static final String CHARSET = "charset";
    private static final String CONTENT_TYPE = "contentType";
    private static final String TIMESTAMP = "timestamp";

    /**
     * How many times in a row we accept that the lock file or its directory was removed by its previous holder before we
     * were able to lock it
     */
    private static final int MAX_STALE_LOCK_FILES = 8;

    /** Locks by absolute normalized entry directory, shared by all instances loaded by the current class loader */
    private static final Map<Path, JvmLock> JVM_LOCKS = new HashMap<>();

    private final Path root;
    private final Duration expiration;
    private final Duration gcAge;
    private final Duration lockRetryDelay;
    private final int lockRetries;
    private final Clock clock;
    /** {@code true} if some entry was looked up through this instance */
    private volatile boolean used;

    PersistentCache(Path root, Duration expiration, Duration gcAge, Duration lockRetryDelay, int lockRetries, Clock clock) {
        this.root = root.toAbsolutePath().normalize();
        this.expiration = expiration;
        this.gcAge = gcAge;
        this.lockRetryDelay = lockRetryDelay;
        this.lockRetries = lockRetries;
        this.clock = clock;
    }

    Path root() {
        return root;
    }

    Duration expiration() {
        return expiration;
    }

    Duration gcAge() {
        return gcAge;
    }

    Duration lockRetryDelay() {
        return lockRetryDelay;
    }

    int lockRetries() {
        return lockRetries;
    }

    /**
     * Locks the entry of the given {@code key} for the current thread, within the current class loader and across
     * processes. Waits until the lock is available.
     *
     * @param key the key of the entry to lock
     * @return the lock that must be closed by the caller, or {@code null} if the entry cannot be locked; in the latter
     *         case, the caller should proceed as if there was no cache
     */
    EntryLock lock(CacheKey key) {
        used = true;
        final Path dir;
        try {
            dir = key.resolve(root);
        } catch (RuntimeException e) {
            log.warnf("Not caching %s: %s", key.uri(), e.toString());
            return null;
        }
        final JvmLock jvmLock = lockInJvm(dir, true);
        try {
            int staleLockFiles = 0;
            int retries = 0;
            while (true) {
                Files.createDirectories(dir);
                final FileChannel channel;
                try {
                    channel = openLockFile(dir);
                } catch (NoSuchFileException e) {
                    /* The directory was removed by some other process between the two calls above */
                    if (++staleLockFiles > MAX_STALE_LOCK_FILES) {
                        throw e;
                    }
                    continue;
                }
                try {
                    final FileLock fileLock = channel.lock();
                    if (channel.size() == 0L) {
                        return new EntryLock(key, dir, jvmLock, channel, fileLock);
                    }
                    /* The lock file was deleted by its previous holder, see EntryLock.deleteLockFile() */
                    channel.close();
                    if (++staleLockFiles > MAX_STALE_LOCK_FILES) {
                        throw new IOException("Unexpected content of " + dir.resolve(LOCK_FILE));
                    }
                } catch (OverlappingFileLockException e) {
                    /* Held through some other channel in the current JVM, most probably by another class loader */
                    channel.close();
                    if (retries >= lockRetries) {
                        throw new IOException(dir.resolve(LOCK_FILE) + " is locked by another class loader of this JVM"
                                + (lockRetries > 0 ? "; gave up after " + lockRetries + " retries" : ""));
                    }
                    retries++;
                    log.debugf("Retry %d of %d in %d ms: lock for %s is held by another class loader of this JVM",
                            retries, lockRetries, lockRetryDelay.toMillis(), key.uri());
                    try {
                        Thread.sleep(lockRetryDelay);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new IOException("Interrupted while waiting for the lock");
                    }
                } catch (IOException | RuntimeException e) {
                    closeQuietly(channel);
                    throw e;
                }
            }
        } catch (IOException | RuntimeException e) {
            unlockInJvm(jvmLock);
            log.warnf("Not caching %s: could not lock %s: %s", key.uri(), dir, e.toString());
            return null;
        }
    }

    /**
     * Removes the entries older than {@link #gcAge}, unless no entry was looked up through this {@link PersistentCache}.
     * Entries that are locked and entries without a readable timestamp are left untouched.
     */
    void collectGarbage() {
        if (!used) {
            return;
        }
        try {
            if (!Files.isDirectory(root)) {
                return;
            }
            final Instant threshold = clock.instant().minus(gcAge);
            /* A symbolic link is a legal root, but walkFileTree() would not follow it */
            final Path realRoot = root.toRealPath();
            final List<Path> oldEntryDirs = new ArrayList<>();
            Files.walkFileTree(realRoot, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (METADATA_FILE.equals(file.getFileName().toString()) && isOlder(file, threshold)) {
                        oldEntryDirs.add(root.resolve(realRoot.relativize(file.getParent()).toString()).normalize());
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException e) {
                    log.debugf("Garbage collection skipping %s: %s", file, e.toString());
                    return FileVisitResult.CONTINUE;
                }
            });
            int collected = 0;
            for (Path dir : oldEntryDirs) {
                if (collect(dir, threshold)) {
                    collected++;
                }
            }
            log.debugf("Garbage collected %d of %d entries older than %s in %s", collected, oldEntryDirs.size(), gcAge,
                    root);
        } catch (IOException | RuntimeException e) {
            log.warnf("Could not garbage collect %s: %s", root, e.toString());
        }
    }

    private boolean collect(Path dir, Instant threshold) {
        final JvmLock jvmLock = lockInJvm(dir, false);
        if (jvmLock == null) {
            log.debugf("Garbage collection skipping locked %s", dir);
            return false;
        }
        try {
            boolean collected = false;
            try (FileChannel channel = openLockFile(dir)) {
                FileLock fileLock;
                try {
                    fileLock = channel.tryLock();
                } catch (OverlappingFileLockException e) {
                    fileLock = null;
                }
                if (fileLock == null) {
                    log.debugf("Garbage collection skipping locked %s", dir);
                    return false;
                }
                /* Check again, now that nobody else can change the entry */
                if (channel.size() == 0L && isOlder(dir.resolve(METADATA_FILE), threshold)) {
                    Files.deleteIfExists(dir.resolve(METADATA_FILE));
                    Files.deleteIfExists(dir.resolve(BODY_FILE));
                    Files.deleteIfExists(dir.resolve(METADATA_FILE + TMP_SUFFIX));
                    Files.deleteIfExists(dir.resolve(BODY_FILE + TMP_SUFFIX));
                    deleteLockFile(dir, channel);
                    collected = true;
                    log.debugf("Garbage collected %s", dir);
                }
            }
            if (collected) {
                deleteEmptyDirectories(dir);
            }
            return collected;
        } catch (NoSuchFileException e) {
            /* Removed by somebody else in the meantime */
            log.debugf("Garbage collection skipping %s: %s", dir, e.toString());
            return false;
        } catch (IOException | RuntimeException e) {
            log.warnf("Could not garbage collect %s: %s", dir, e.toString());
            return false;
        } finally {
            unlockInJvm(jvmLock);
        }
    }

    private static boolean isOlder(Path metadataFile, Instant threshold) {
        try {
            final String timestamp = loadProperties(metadataFile).getProperty(TIMESTAMP);
            return timestamp != null && Instant.parse(timestamp.trim()).isBefore(threshold);
        } catch (IOException | RuntimeException e) {
            /* No readable timestamp */
            return false;
        }
    }

    private static FileChannel openLockFile(Path dir) throws IOException {
        return FileChannel.open(dir.resolve(LOCK_FILE), StandardOpenOption.CREATE, StandardOpenOption.READ,
                StandardOpenOption.WRITE);
    }

    /**
     * Deletes the lock file that is locked through the given {@code channel}.
     * <p>
     * Others may have opened the file already and may be waiting for the lock. Once they get it, they would hold a lock
     * on a file that is not reachable anymore, while yet others would create and lock a new file. Therefore the file is
     * marked by making it non-empty before it is deleted. Whoever locks a non-empty lock file has to start over.
     */
    private static void deleteLockFile(Path dir, FileChannel channel) throws IOException {
        channel.write(ByteBuffer.wrap(new byte[] { 'd' }), 0L);
        try {
            Files.delete(dir.resolve(LOCK_FILE));
        } catch (IOException e) {
            channel.truncate(0L);
            throw e;
        }
    }

    /**
     * Deletes the given directory and its ancestors up to the {@link #root} (exclusive) as long as they are empty.
     */
    private void deleteEmptyDirectories(Path dir) {
        Path d = dir;
        while (d != null && d.startsWith(root) && !d.equals(root)) {
            try {
                Files.delete(d);
            } catch (IOException e) {
                /* Not empty or not deletable */
                return;
            }
            d = d.getParent();
        }
    }

    private static Properties loadProperties(Path file) throws IOException {
        final Properties result = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            result.load(in);
        }
        return result;
    }

    private static String mandatory(Properties props, String key) {
        final String result = props.getProperty(key);
        if (result == null) {
            throw new IllegalArgumentException("Missing " + key);
        }
        return result.trim();
    }

    private static void appendProperty(StringBuilder out, String key, String value) {
        out.append(key).append('=');
        for (int i = 0; i < value.length(); i++) {
            final char c = value.charAt(i);
            switch (c) {
                case '\\':
                    out.append("\\\\");
                    break;
                case ' ':
                    out.append(i == 0 ? "\\ " : " ");
                    break;
                default:
                    if (c < 0x20 || c > 0x7e) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                    break;
            }
        }
        out.append('\n');
    }

    private static void move(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void closeQuietly(FileChannel channel) {
        try {
            channel.close();
        } catch (IOException | RuntimeException e) {
            log.debugf("Could not close a lock file: %s", e.toString());
        }
    }

    private static JvmLock lockInJvm(Path dir, boolean wait) {
        final JvmLock result;
        synchronized (JVM_LOCKS) {
            result = JVM_LOCKS.computeIfAbsent(dir, JvmLock::new);
            result.users++;
        }
        if (wait) {
            result.lock.lock();
            return result;
        }
        if (result.lock.tryLock()) {
            return result;
        }
        forget(result);
        return null;
    }

    private static void unlockInJvm(JvmLock jvmLock) {
        jvmLock.lock.unlock();
        forget(jvmLock);
    }

    private static void forget(JvmLock jvmLock) {
        synchronized (JVM_LOCKS) {
            if (--jvmLock.users == 0) {
                JVM_LOCKS.remove(jvmLock.dir);
            }
        }
    }

    private static final class JvmLock {
        private final Path dir;
        private final ReentrantLock lock = new ReentrantLock();
        /** The number of threads holding or waiting for {@link #lock}; guarded by {@link PersistentCache#JVM_LOCKS} */
        private int users;

        JvmLock(Path dir) {
            this.dir = dir;
        }
    }

    /**
     * An exclusive lock on a single entry.
     *
     * @since 4.0.0
     */
    final class EntryLock implements AutoCloseable {
        private final CacheKey key;
        private final Path dir;
        private final Path metadataFile;
        private final Path bodyFile;
        private final JvmLock jvmLock;
        private final FileChannel channel;
        private final FileLock fileLock;

        private EntryLock(CacheKey key, Path dir, JvmLock jvmLock, FileChannel channel, FileLock fileLock) {
            this.key = key;
            this.dir = dir;
            this.metadataFile = dir.resolve(METADATA_FILE);
            this.bodyFile = dir.resolve(BODY_FILE);
            this.jvmLock = jvmLock;
            this.channel = channel;
            this.fileLock = fileLock;
        }

        /**
         * @return the stored {@link Response} or {@code null} if there is none, if it is older than
         *         {@link PersistentCache#expiration}, if it belongs to another URI or if it is not usable for any other reason
         */
        Response read() {
            final String uri = key.uri();
            try {
                if (!Files.isRegularFile(metadataFile) || !Files.isRegularFile(bodyFile)) {
                    log.debugf("Cache miss: %s", uri);
                    return null;
                }
                final int statusCode;
                final Charset charset;
                final String contentType;
                final Instant timestamp;
                try {
                    final Properties props = loadProperties(metadataFile);
                    final String storedUri = mandatory(props, URI);
                    if (!key.normalizedUri().equals(CacheKey.normalize(storedUri))) {
                        log.debugf("Cache miss: %s; %s holds %s", uri, dir, storedUri);
                        return null;
                    }
                    statusCode = Integer.parseInt(mandatory(props, STATUS_CODE));
                    if (statusCode < 200 || statusCode >= 300) {
                        throw new IllegalArgumentException("Unexpected " + STATUS_CODE + " " + statusCode);
                    }
                    charset = Charset.forName(mandatory(props, CHARSET));
                    contentType = props.getProperty(CONTENT_TYPE);
                    timestamp = Instant.parse(mandatory(props, TIMESTAMP));
                } catch (RuntimeException e) {
                    log.debugf("Cache miss: %s; unusable %s: %s", uri, metadataFile, e.toString());
                    return null;
                }
                if (timestamp.plus(expiration).isBefore(clock.instant())) {
                    log.debugf("Cache entry expired: %s; fetched %s", uri, timestamp);
                    return null;
                }
                final byte[] body = Files.readAllBytes(bodyFile);
                log.debugf("Cache hit: %s; fetched %s", uri, timestamp);
                return new Response(uri, statusCode, charset, contentType, body);
            } catch (IOException | RuntimeException e) {
                log.warnf("Not using the cache for %s: could not read %s: %s", uri, dir, e.toString());
                return null;
            }
        }

        /**
         * Stores the given {@code response}, replacing the current entry, if any.
         * <p>
         * The metadata file is removed first and created last, so that an incomplete entry is never usable.
         *
         * @param response the {@link Response} to store
         */
        void write(Response response) {
            final Path metadataTmp = dir.resolve(METADATA_FILE + TMP_SUFFIX);
            final Path bodyTmp = dir.resolve(BODY_FILE + TMP_SUFFIX);
            try {
                final Instant timestamp = clock.instant().truncatedTo(ChronoUnit.SECONDS);
                final StringBuilder metadata = new StringBuilder();
                appendProperty(metadata, URI, key.uri());
                appendProperty(metadata, STATUS_CODE, String.valueOf(response.statusCode()));
                appendProperty(metadata, CHARSET, response.charset().name());
                if (response.contentType() != null) {
                    appendProperty(metadata, CONTENT_TYPE, response.contentType());
                }
                appendProperty(metadata, TIMESTAMP, timestamp.toString());

                Files.createDirectories(dir);
                Files.write(bodyTmp, response.body());
                Files.writeString(metadataTmp, metadata, StandardCharsets.ISO_8859_1);
                Files.deleteIfExists(metadataFile);
                move(bodyTmp, bodyFile);
                move(metadataTmp, metadataFile);
                log.debugf("Cache entry written: %s to %s", key.uri(), dir);
            } catch (IOException | RuntimeException e) {
                log.warnf("Not caching %s: could not write %s: %s", key.uri(), dir, e.toString());
                try {
                    Files.deleteIfExists(metadataTmp);
                    Files.deleteIfExists(bodyTmp);
                } catch (IOException | RuntimeException e1) {
                    log.debugf("Could not clean up %s: %s", dir, e1.toString());
                }
            }
        }

        /**
         * Releases this lock. If there is still no entry in the directory, the lock file and the directories that are
         * empty are removed, so that URIs that cannot be fetched leave nothing behind.
         *
         * @since 4.0.0
         */
        @Override
        public void close() {
            boolean lockFileDeleted = false;
            try {
                if (!Files.exists(metadataFile) && !Files.exists(bodyFile)) {
                    deleteLockFile(dir, channel);
                    lockFileDeleted = true;
                }
            } catch (IOException | RuntimeException e) {
                log.debugf("Could not delete the lock file in %s: %s", dir, e.toString());
            }
            try {
                fileLock.release();
            } catch (IOException | RuntimeException e) {
                log.debugf("Could not release the lock in %s: %s", dir, e.toString());
            }
            closeQuietly(channel);
            if (lockFileDeleted) {
                deleteEmptyDirectories(dir);
            }
            unlockInJvm(jvmLock);
        }
    }
}
