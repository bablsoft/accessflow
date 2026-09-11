package com.bablsoft.accessflow.access.internal.web;

import com.bablsoft.accessflow.access.api.BreakGlassGrant;
import com.bablsoft.accessflow.access.api.PrivilegedAccessEvidence;
import com.bablsoft.accessflow.access.api.PrivilegedAccessRow;
import com.bablsoft.accessflow.access.api.QueryAdminBypass;
import com.bablsoft.accessflow.access.api.StandingBypassKind;
import com.bablsoft.accessflow.core.api.DatasourcePermissionSourceKind;
import com.bablsoft.accessflow.core.api.PageResponse;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PrivilegedAccessResponseTest {

    @Test
    void fromMapsEveryFieldIncludingNestedGrantsAndEvidence() {
        var userId = UUID.randomUUID();
        var roleId = UUID.randomUUID();
        var dsId = UUID.randomUUID();
        var rowId = UUID.randomUUID();
        var groupId = UUID.randomUUID();
        var expiry = Instant.parse("2026-10-01T00:00:00Z");
        var last = Instant.parse("2026-09-10T14:02:11Z");
        var row = new PrivilegedAccessRow(userId, "root@example.com", "Root", roleId, "ADMIN", true,
                EnumSet.of(StandingBypassKind.QUERY_ADMIN, StandingBypassKind.BREAK_GLASS),
                new QueryAdminBypass(roleId, "ADMIN", true),
                List.of(new BreakGlassGrant(dsId, "analytics", DatasourcePermissionSourceKind.GROUP,
                        rowId, groupId, "oncall", expiry)),
                new PrivilegedAccessEvidence(143L, last, 2L, null));

        var response = PrivilegedAccessResponse.from(row);

        assertThat(response.userId()).isEqualTo(userId);
        assertThat(response.email()).isEqualTo("root@example.com");
        assertThat(response.displayName()).isEqualTo("Root");
        assertThat(response.roleId()).isEqualTo(roleId);
        assertThat(response.roleName()).isEqualTo("ADMIN");
        assertThat(response.systemRole()).isTrue();
        assertThat(response.bypassKinds())
                .containsExactly(StandingBypassKind.QUERY_ADMIN, StandingBypassKind.BREAK_GLASS);
        assertThat(response.queryAdmin())
                .isEqualTo(new PrivilegedAccessResponse.QueryAdminBypassResponse(roleId, "ADMIN", true));
        assertThat(response.breakGlassGrants()).containsExactly(
                new PrivilegedAccessResponse.BreakGlassGrantResponse(dsId, "analytics",
                        DatasourcePermissionSourceKind.GROUP, rowId, groupId, "oncall", expiry));
        assertThat(response.evidence())
                .isEqualTo(new PrivilegedAccessResponse.EvidenceResponse(143L, last, 2L, null));
    }

    @Test
    void aBreakGlassOnlyRowHasANullQueryAdmin() {
        var row = new PrivilegedAccessRow(UUID.randomUUID(), "oncall@example.com", null,
                UUID.randomUUID(), "ANALYST", true, EnumSet.of(StandingBypassKind.BREAK_GLASS), null,
                List.of(new BreakGlassGrant(UUID.randomUUID(), "payments-prod",
                        DatasourcePermissionSourceKind.DIRECT, UUID.randomUUID(), null, null, null)),
                PrivilegedAccessEvidence.none());

        var response = PrivilegedAccessResponse.from(row);

        assertThat(response.queryAdmin()).isNull();
        assertThat(response.displayName()).isNull();
        assertThat(response.breakGlassGrants()).hasSize(1);
        assertThat(response.breakGlassGrants().get(0).groupId()).isNull();
        assertThat(response.breakGlassGrants().get(0).expiresAt()).isNull();
        assertThat(response.evidence().submittedQueryCount()).isZero();
    }

    @Test
    void pageResponseCarriesThePagingEnvelope() {
        var row = new PrivilegedAccessRow(UUID.randomUUID(), "root@example.com", "Root",
                UUID.randomUUID(), "ADMIN", true, EnumSet.of(StandingBypassKind.QUERY_ADMIN),
                new QueryAdminBypass(UUID.randomUUID(), "ADMIN", true), List.of(),
                PrivilegedAccessEvidence.none());

        var page = PrivilegedAccessPageResponse.from(new PageResponse<>(List.of(row), 2, 20, 41L, 3));

        assertThat(page.content()).hasSize(1);
        assertThat(page.content().get(0).email()).isEqualTo("root@example.com");
        assertThat(page.page()).isEqualTo(2);
        assertThat(page.size()).isEqualTo(20);
        assertThat(page.totalElements()).isEqualTo(41L);
        assertThat(page.totalPages()).isEqualTo(3);
    }
}
