package com.bablsoft.accessflow.access.internal.web;

import com.bablsoft.accessflow.access.api.BreakGlassGrant;
import com.bablsoft.accessflow.access.api.PrivilegedAccessEvidence;
import com.bablsoft.accessflow.access.api.PrivilegedAccessRow;
import com.bablsoft.accessflow.access.api.QueryAdminBypass;
import com.bablsoft.accessflow.access.api.StandingBypassKind;
import com.bablsoft.accessflow.core.api.DatasourcePermissionSourceKind;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One row of the privileged-access report (#968).
 *
 * <p>{@code @JsonInclude(ALWAYS)} overrides the global {@code non_null} default (same rationale as
 * {@code OverProvisionedAccessResponse}): a null {@code query_admin}, a null {@code expires_at}
 * ("never expires") and a null {@code last_*_at} ("never") are facts the client renders, and an
 * omitted key is far easier to misread as an unset optional than an explicit null.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record PrivilegedAccessResponse(
        UUID userId,
        String email,
        String displayName,
        UUID roleId,
        String roleName,
        boolean systemRole,
        List<StandingBypassKind> bypassKinds,
        QueryAdminBypassResponse queryAdmin,
        List<BreakGlassGrantResponse> breakGlassGrants,
        EvidenceResponse evidence) {

    public static PrivilegedAccessResponse from(PrivilegedAccessRow row) {
        return new PrivilegedAccessResponse(
                row.userId(),
                row.email(),
                row.displayName(),
                row.roleId(),
                row.roleName(),
                row.systemRole(),
                List.copyOf(row.bypassKinds()),
                QueryAdminBypassResponse.from(row.queryAdmin()),
                row.breakGlassGrants().stream().map(BreakGlassGrantResponse::from).toList(),
                EvidenceResponse.from(row.evidence()));
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record QueryAdminBypassResponse(UUID roleId, String roleName, boolean systemRole) {

        static QueryAdminBypassResponse from(QueryAdminBypass bypass) {
            return bypass == null ? null
                    : new QueryAdminBypassResponse(bypass.roleId(), bypass.roleName(), bypass.systemRole());
        }
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record BreakGlassGrantResponse(
            UUID datasourceId,
            String datasourceName,
            DatasourcePermissionSourceKind sourceKind,
            UUID sourceId,
            UUID groupId,
            String groupName,
            Instant expiresAt) {

        static BreakGlassGrantResponse from(BreakGlassGrant grant) {
            return new BreakGlassGrantResponse(grant.datasourceId(), grant.datasourceName(),
                    grant.sourceKind(), grant.sourceId(), grant.groupId(), grant.groupName(),
                    grant.expiresAt());
        }
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record EvidenceResponse(
            long submittedQueryCount,
            Instant lastSubmittedAt,
            long breakGlassExecutionCount,
            Instant lastBreakGlassAt) {

        static EvidenceResponse from(PrivilegedAccessEvidence evidence) {
            return new EvidenceResponse(evidence.submittedQueryCount(), evidence.lastSubmittedAt(),
                    evidence.breakGlassExecutionCount(), evidence.lastBreakGlassAt());
        }
    }
}
