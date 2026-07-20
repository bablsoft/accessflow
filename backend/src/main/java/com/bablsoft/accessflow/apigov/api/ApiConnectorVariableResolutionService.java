package com.bablsoft.accessflow.apigov.api;

import java.util.Map;
import java.util.UUID;

/**
 * Evaluates a connector's dynamic variables for one outbound call (AF-613).
 *
 * <p>Called from the execution path <em>after</em> auth headers have been resolved — including a
 * freshly minted OAuth2 bearer token — so an expression may sign the finished {@code Authorization}
 * header. Variables are evaluated in dependency order over the DAG formed by their
 * {@code {{var.x}}} references.
 *
 * <p>{@code overrides} carries per-request submitter-supplied values for variables marked
 * overridable. An override <strong>replaces</strong> the value entirely — the kind, algorithm and
 * encoding are not re-applied — and is inserted as an opaque literal that is never itself expanded
 * as a template. Variables that depend on an overridden one still recompute over the new value.
 * Names not marked overridable are ignored here; the submit path rejects them up front.
 */
public interface ApiConnectorVariableResolutionService {

    ResolvedApiVariables resolve(UUID organizationId, UUID connectorId,
                                 ApiVariableRequestContext context, Map<String, String> overrides);
}
