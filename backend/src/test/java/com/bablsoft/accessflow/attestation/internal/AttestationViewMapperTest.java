package com.bablsoft.accessflow.attestation.internal;

import com.bablsoft.accessflow.attestation.api.AttestationCampaignScope;
import com.bablsoft.accessflow.attestation.api.AttestationCampaignStatus;
import com.bablsoft.accessflow.attestation.api.AttestationItemCloseReason;
import com.bablsoft.accessflow.attestation.api.AttestationItemDecision;
import com.bablsoft.accessflow.attestation.api.AttestationPendingDefault;
import com.bablsoft.accessflow.attestation.internal.persistence.entity.AttestationCampaignEntity;
import com.bablsoft.accessflow.attestation.internal.persistence.entity.AttestationItemEntity;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AttestationViewMapperTest {

    @Test
    void mapsCampaignWithCountsAndDatasourceName() {
        var e = new AttestationCampaignEntity();
        e.setId(UUID.randomUUID());
        e.setOrganizationId(UUID.randomUUID());
        e.setName("Q3");
        e.setDescription("desc");
        e.setScope(AttestationCampaignScope.DATASOURCE);
        e.setDatasourceId(UUID.randomUUID());
        e.setStatus(AttestationCampaignStatus.OPEN);
        e.setPendingDefault(AttestationPendingDefault.REVOKE);
        e.setScheduledOpenAt(Instant.parse("2026-07-01T00:00:00Z"));
        e.setDueAt(Instant.parse("2026-07-08T00:00:00Z"));
        e.setTotalItems(9);
        e.setCreatedBy(UUID.randomUUID());

        var v = AttestationViewMapper.toCampaignView(e, "Production", 4, 3, 2);

        assertThat(v.id()).isEqualTo(e.getId());
        assertThat(v.name()).isEqualTo("Q3");
        assertThat(v.datasourceName()).isEqualTo("Production");
        assertThat(v.status()).isEqualTo(AttestationCampaignStatus.OPEN);
        assertThat(v.pendingDefault()).isEqualTo(AttestationPendingDefault.REVOKE);
        assertThat(v.totalItems()).isEqualTo(9);
        assertThat(v.pendingItems()).isEqualTo(4);
        assertThat(v.certifiedItems()).isEqualTo(3);
        assertThat(v.revokedItems()).isEqualTo(2);
    }

    @Test
    void mapsItem() {
        var e = new AttestationItemEntity();
        e.setId(UUID.randomUUID());
        e.setCampaignId(UUID.randomUUID());
        e.setOrganizationId(UUID.randomUUID());
        e.setPermissionId(UUID.randomUUID());
        e.setDatasourceId(UUID.randomUUID());
        e.setDatasourceName("Production");
        e.setSubjectUserId(UUID.randomUUID());
        e.setSubjectUserEmail("alice@example.com");
        e.setSubjectUserDisplayName("Alice");
        e.setCanRead(true);
        e.setCanWrite(true);
        e.setDecision(AttestationItemDecision.REVOKED);
        e.setCloseReason(AttestationItemCloseReason.REVIEWER);
        e.setDecidedBy(UUID.randomUUID());
        e.setDecidedAt(Instant.parse("2026-07-03T00:00:00Z"));
        e.setDecisionComment("no longer needed");

        var v = AttestationViewMapper.toItemView(e);

        assertThat(v.subjectUserEmail()).isEqualTo("alice@example.com");
        assertThat(v.canRead()).isTrue();
        assertThat(v.canWrite()).isTrue();
        assertThat(v.canDdl()).isFalse();
        assertThat(v.decision()).isEqualTo(AttestationItemDecision.REVOKED);
        assertThat(v.closeReason()).isEqualTo(AttestationItemCloseReason.REVIEWER);
        assertThat(v.decisionComment()).isEqualTo("no longer needed");
    }

    @Test
    void mapsSummary() {
        var e = new AttestationCampaignEntity();
        e.setId(UUID.randomUUID());
        e.setOrganizationId(UUID.randomUUID());
        e.setName("Q3");
        e.setDueAt(Instant.parse("2026-07-08T00:00:00Z"));
        var s = AttestationViewMapper.toSummary(e);
        assertThat(s.id()).isEqualTo(e.getId());
        assertThat(s.name()).isEqualTo("Q3");
        assertThat(s.dueAt()).isEqualTo(e.getDueAt());
    }
}
