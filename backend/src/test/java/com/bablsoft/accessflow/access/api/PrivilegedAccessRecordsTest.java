package com.bablsoft.accessflow.access.api;

import com.bablsoft.accessflow.core.api.DatasourcePermissionSourceKind;
import com.bablsoft.accessflow.core.api.QuerySubmitterEvidence;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PrivilegedAccessRecordsTest {

    @Test
    void evidenceFromNullIsNone() {
        assertThat(PrivilegedAccessEvidence.from(null)).isEqualTo(PrivilegedAccessEvidence.none());
        assertThat(PrivilegedAccessEvidence.none())
                .isEqualTo(new PrivilegedAccessEvidence(0L, null, 0L, null));
    }

    @Test
    void evidenceCopiesEveryField() {
        var last = Instant.parse("2026-09-10T14:02:11Z");
        var lastBg = Instant.parse("2026-08-30T03:14:00Z");
        var source = new QuerySubmitterEvidence(UUID.randomUUID(), 143L, last, 2L, lastBg);

        assertThat(PrivilegedAccessEvidence.from(source))
                .isEqualTo(new PrivilegedAccessEvidence(143L, last, 2L, lastBg));
    }

    @Test
    void rowDefensivelyCopiesCollectionsAndDefaultsEvidence() {
        var kinds = EnumSet.of(StandingBypassKind.BREAK_GLASS, StandingBypassKind.QUERY_ADMIN);
        var grant = new BreakGlassGrant(UUID.randomUUID(), "payments-prod",
                DatasourcePermissionSourceKind.DIRECT, UUID.randomUUID(), null, null, null);
        var grants = new java.util.ArrayList<>(List.of(grant));

        var row = new PrivilegedAccessRow(UUID.randomUUID(), "root@example.com", "Root",
                UUID.randomUUID(), "ADMIN", true, kinds,
                new QueryAdminBypass(UUID.randomUUID(), "ADMIN", true), grants, null);
        kinds.clear();
        grants.clear();

        // Enum order, whatever order the caller built the set in.
        assertThat(row.bypassKinds())
                .containsExactly(StandingBypassKind.QUERY_ADMIN, StandingBypassKind.BREAK_GLASS);
        assertThat(row.breakGlassGrants()).containsExactly(grant);
        assertThat(row.evidence()).isEqualTo(PrivilegedAccessEvidence.none());
        assertThatThrownBy(() -> row.bypassKinds().add(StandingBypassKind.QUERY_ADMIN))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rowToleratesNullCollections() {
        var row = new PrivilegedAccessRow(UUID.randomUUID(), "a@b.c", null, null, "ANALYST", true,
                null, null, null, PrivilegedAccessEvidence.none());

        assertThat(row.bypassKinds()).isEmpty();
        assertThat(row.breakGlassGrants()).isEmpty();
        assertThat(new PrivilegedAccessRow(UUID.randomUUID(), "a@b.c", null, null, "ANALYST", true,
                Set.of(), null, List.of(), null).bypassKinds()).isEmpty();
    }

    @Test
    void emptyQueryHasNoFilters() {
        assertThat(PrivilegedAccessQuery.empty()).isEqualTo(new PrivilegedAccessQuery(null, null));
    }
}
