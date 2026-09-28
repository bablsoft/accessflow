package com.bablsoft.accessflow.core.api;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Resolves the per-table row cap (#934) for one query. A policy matches when it applies to the
 * submitter (empty {@code applies_to_*} ⇒ everyone) and names a table in
 * {@code referencedTables} — the normalized {@link SqlParseResult#referencedTables()} set. Matching
 * is deliberately lenient because a match can only lower the cap: an unqualified reference matches
 * a policy on that table in any schema, and a policy without a schema matches the table in any
 * schema. An empty {@code referencedTables} matches nothing, leaving the datasource cap in force.
 */
public interface RowLimitPolicyResolutionService {

    Optional<AppliedRowLimit> resolve(UUID organizationId, UUID datasourceId, UUID requesterUserId,
                                      Set<String> referencedTables);

    /**
     * Every enabled policy on the datasource that targets the user, regardless of table, with the
     * reasons — the effective-permission explorer's view (#946). A listed policy lowers the cap
     * only for a query that references its table.
     */
    List<ApplicableRowLimitPolicy> findApplicable(UUID organizationId, UUID datasourceId,
                                                  UUID requesterUserId);
}
