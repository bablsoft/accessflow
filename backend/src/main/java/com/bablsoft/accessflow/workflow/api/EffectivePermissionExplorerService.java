package com.bablsoft.accessflow.workflow.api;

import java.util.UUID;

/**
 * "What can this user actually do on this datasource right now?" (#946). A read-only assembly of
 * the resolution services enforcement uses — the grant merge, masking, row security, row-limit
 * policies and the executor's row-cap clamp — with the provenance of every element. It never
 * computes a merge of its own, so it cannot disagree with enforcement.
 *
 * <p>Throws {@code UserNotFoundException} / {@code DatasourceNotFoundException} when either is
 * outside {@code organizationId}.
 */
public interface EffectivePermissionExplorerService {

    EffectivePermissionExplanation explain(UUID organizationId, UUID userId, UUID datasourceId);
}
