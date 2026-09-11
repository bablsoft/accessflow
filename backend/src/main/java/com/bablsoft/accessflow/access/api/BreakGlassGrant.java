package com.bablsoft.accessflow.access.api;

import com.bablsoft.accessflow.core.api.DatasourcePermissionSourceKind;

import java.time.Instant;
import java.util.UUID;

/**
 * One unexpired {@code can_break_glass} contribution on an active datasource (#968). A
 * {@code DIRECT} grant is a {@code datasource_user_permissions} row ({@code sourceId} = that row);
 * a {@code GROUP} grant is a {@code datasource_group_permissions} row reached through the named
 * group. {@code expiresAt} null means the grant never expires.
 */
public record BreakGlassGrant(
        UUID datasourceId,
        String datasourceName,
        DatasourcePermissionSourceKind sourceKind,
        UUID sourceId,
        UUID groupId,
        String groupName,
        Instant expiresAt
) {
}
