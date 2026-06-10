package com.bablsoft.accessflow.core.api;

/**
 * Spring-free message resolution handed to a {@link QueryEngine} via its
 * {@link QueryEngineContext}. The host backs it with its {@code MessageSource} and the calling
 * thread's locale at resolution time, so engine-raised errors are localized exactly like host
 * errors — the i18n keys an engine uses (e.g. {@code error.mongo.*}) live in the host's
 * {@code messages.properties} and are part of the host&harr;plugin contract. Unknown keys resolve
 * to the key itself rather than throwing, so a plugin newer than the host degrades gracefully.
 */
@FunctionalInterface
public interface EngineMessages {

    /** Resolve {@code key} with optional {@code MessageFormat} arguments for the current locale. */
    String get(String key, Object... args);
}
