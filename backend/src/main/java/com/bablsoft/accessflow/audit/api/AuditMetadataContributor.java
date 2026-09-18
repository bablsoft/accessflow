package com.bablsoft.accessflow.audit.api;

import java.util.Map;

/**
 * Extension point for keys every audit row written on the current thread should carry (#874).
 * Implementations are discovered as Spring beans and consulted by {@code AuditLogService.record}
 * immediately before the metadata is serialised and hashed — so a contributed key is inside the
 * tamper-evident chain exactly like an explicit one.
 *
 * <p>Contract:
 * <ul>
 *   <li>Contributed keys <em>lose</em> to keys the caller placed on the {@link AuditEntry}: the
 *       explicit {@code trigger=} of a system-attributed row is never clobbered.</li>
 *   <li>A contributor must return an empty map when it has nothing to say — off the request thread
 *       (scheduled jobs, after-commit listeners) there is no request to describe.</li>
 *   <li>A contributor should never throw; if it does, the row is still written without its keys
 *       and the failure is logged. A provenance bug must not lose an audit row.</li>
 * </ul>
 *
 * <p>This lives in {@code audit.api} precisely so the audit module never depends on the modules
 * that know what a request is (security, serviceaccounts). The interface is the dependency
 * inversion.
 */
public interface AuditMetadataContributor {

    Map<String, Object> contribute();
}
