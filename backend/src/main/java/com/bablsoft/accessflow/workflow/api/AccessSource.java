package com.bablsoft.accessflow.workflow.api;

import java.time.Instant;
import java.util.UUID;

/**
 * One reason a user can reach a table (issue AF-859).
 *
 * @param sourceId                the granting row, or {@code null} for
 *                                {@link AccessSourceKind#QUERY_ADMIN_BYPASS}, which has no row
 * @param grantsCapability        whether this source alone grants the requested statement class.
 *                                A source can contribute allow-list coverage without the capability,
 *                                or the other way round — the effective permission is the union
 * @param coveringAllowListEntry  the entry that covers the table — the schema or the qualified table
 *                                — or {@code null} under {@link TableScope#ALL_TABLES}
 * @param expiresAt               {@code null} means the source never expires
 * @param preApproveQueries       set only on {@link AccessSourceKind#JIT_GRANT}: queries under it
 *                                also skip review
 */
public record AccessSource(AccessSourceKind kind, UUID sourceId, UUID groupId, String groupName,
                           boolean grantsCapability, TableScope tableScope,
                           String coveringAllowListEntry, Instant expiresAt,
                           boolean preApproveQueries) {
}
