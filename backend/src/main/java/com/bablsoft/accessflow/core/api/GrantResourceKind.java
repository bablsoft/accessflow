package com.bablsoft.accessflow.core.api;

/**
 * The kind of resource a standing grant covers (#625). Lives in {@code core.api} rather than
 * alongside {@code access.api.AccessResourceKind} because both the {@code audit} and {@code access}
 * modules need it: {@code access} already depends on {@code audit}, so an {@code audit → access}
 * import would close a module cycle. {@code core} depends on nothing.
 *
 * <p>Distinct from {@code AccessResourceKind}, which names the target of a JIT access
 * <em>request</em>; this names the resource a materialised, standing grant applies to.
 */
public enum GrantResourceKind {
    /** A {@code datasource_user_permissions} grant — scope is its allowed tables. */
    DATASOURCE,
    /** An {@code api_connector_user_permissions} grant — scope is its allowed operations. */
    API_CONNECTOR
}
