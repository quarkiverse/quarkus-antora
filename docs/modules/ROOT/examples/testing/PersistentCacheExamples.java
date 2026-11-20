package io.quarkiverse.antora.test;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;

import io.quarkiverse.antorassured.AntorAssured;
import io.quarkiverse.antorassured.LinkValidator.LinkValidatorImpl;

/**
 * Code snippets included in {@code testing.adoc}. They are here to make sure that they compile.
 */
public class PersistentCacheExamples {

    public void customCache() {
        // tag::customCache[]
        AntorAssured
                .links()
                .excludeEditThisPage()
                .validate(
                        new LinkValidatorImpl(
                                /* Where the pages are stored */
                                Path.of("target/antorassured-cache"),
                                /* Pages fetched more than 12 hours ago are fetched again */
                                Duration.ofHours(12),
                                /* Pages fetched more than 30 days ago are deleted from the cache directory */
                                Duration.ofDays(30),
                                LinkValidatorImpl.DEFAULT_CACHE_LOCK_RETRY_DELAY,
                                LinkValidatorImpl.DEFAULT_CACHE_LOCK_RETRIES,
                                /* Which URIs may be cached: anything but the site under test running on localhost */
                                LinkValidatorImpl.notLoopback(),
                                Clock.systemUTC()))
                .assertValid();
        // end::customCache[]
    }

    public void noCache() {
        // tag::noCache[]
        AntorAssured
                .links()
                .excludeEditThisPage()
                .validate(
                        new LinkValidatorImpl(
                                LinkValidatorImpl.defaultCacheDirectory(),
                                LinkValidatorImpl.DEFAULT_CACHE_EXPIRATION,
                                LinkValidatorImpl.DEFAULT_CACHE_GC_AGE,
                                LinkValidatorImpl.DEFAULT_CACHE_LOCK_RETRY_DELAY,
                                LinkValidatorImpl.DEFAULT_CACHE_LOCK_RETRIES,
                                /* No URI may be cached */
                                uri -> false,
                                Clock.systemUTC()))
                .assertValid();
        // end::noCache[]
    }

}
