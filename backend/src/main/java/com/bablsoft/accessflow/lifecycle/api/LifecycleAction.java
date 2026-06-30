package com.bablsoft.accessflow.lifecycle.api;

/**
 * What a retention policy (or erasure execution) does to matched data. Mirrors the PostgreSQL
 * {@code lifecycle_action} enum.
 *
 * <ul>
 *   <li>{@code HARD_DELETE} — rows are physically removed.</li>
 *   <li>{@code SOFT_DELETE} — a {@code DELETE} is rewritten to set a marker column; the proxy then
 *       filters marked rows out of reads.</li>
 *   <li>{@code PSEUDONYMIZE} — matched columns resolve to a {@link LifecycleTransform} at read time,
 *       so PII is irreversibly transformed while row presence (and aggregates) survive.</li>
 * </ul>
 */
public enum LifecycleAction {
    HARD_DELETE,
    SOFT_DELETE,
    PSEUDONYMIZE
}
