package com.bablsoft.accessflow.core.api;

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
}
