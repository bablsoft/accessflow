package com.bablsoft.accessflow.core.api;

import java.util.Optional;
import java.util.UUID;

/**
 * Resolves the resource a scoped review delegation points at (#622), one implementation per
 * {@link DelegationScopeKind}.
 *
 * <p>This exists to invert a dependency that would otherwise be a cycle: {@code core} owns the
 * delegation table and must validate that a scoped delegation names a real resource, but an
 * {@code API_CONNECTOR} scope resolves against {@code apigov}, which already depends on
 * {@code core}. Rather than reach forwards, {@code core} collects implementations of this interface
 * and each owning module supplies its own.
 *
 * <p>Resolution is organization-scoped: a resource in another tenant must resolve to empty, so a
 * delegation can never be scoped across an organization boundary.
 */
public interface ReviewDelegationScopeResolver {

    /** The scope kind this resolver answers for. Exactly one implementation per kind. */
    DelegationScopeKind supportedKind();

    /**
     * The resource's display name, or empty when it does not exist in that organization — which
     * the service treats as an invalid scope on write, and eligibility treats as no match on read.
     */
    Optional<String> resolveName(UUID organizationId, UUID scopeId);
}
