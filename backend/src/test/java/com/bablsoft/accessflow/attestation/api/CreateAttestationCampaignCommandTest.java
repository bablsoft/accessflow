package com.bablsoft.accessflow.attestation.api;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CreateAttestationCampaignCommandTest {

    private final UUID org = UUID.randomUUID();
    private final UUID creator = UUID.randomUUID();
    private final UUID datasource = UUID.randomUUID();
    private final Instant open = Instant.parse("2026-07-01T00:00:00Z");
    private final Instant due = Instant.parse("2026-07-08T00:00:00Z");

    @Test
    void buildsDatasourceScopedCampaign() {
        var cmd = new CreateAttestationCampaignCommand(org, creator, "Q3 review", "desc",
                AttestationCampaignScope.DATASOURCE, datasource, AttestationPendingDefault.REVOKE,
                open, due);
        assertThat(cmd.datasourceId()).isEqualTo(datasource);
        assertThat(cmd.pendingDefault()).isEqualTo(AttestationPendingDefault.REVOKE);
    }

    @Test
    void defaultsPendingDefaultToKeep() {
        var cmd = new CreateAttestationCampaignCommand(org, creator, "Org review", null,
                AttestationCampaignScope.ORGANIZATION, null, null, open, due);
        assertThat(cmd.pendingDefault()).isEqualTo(AttestationPendingDefault.KEEP);
    }

    @Test
    void rejectsMissingOrganization() {
        assertThatThrownBy(() -> new CreateAttestationCampaignCommand(null, creator, "n", null,
                AttestationCampaignScope.ORGANIZATION, null, null, open, due))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsMissingCreator() {
        assertThatThrownBy(() -> new CreateAttestationCampaignCommand(org, null, "n", null,
                AttestationCampaignScope.ORGANIZATION, null, null, open, due))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsBlankName() {
        assertThatThrownBy(() -> new CreateAttestationCampaignCommand(org, creator, "  ", null,
                AttestationCampaignScope.ORGANIZATION, null, null, open, due))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsMissingScope() {
        assertThatThrownBy(() -> new CreateAttestationCampaignCommand(org, creator, "n", null,
                null, null, null, open, due))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsMissingDates() {
        assertThatThrownBy(() -> new CreateAttestationCampaignCommand(org, creator, "n", null,
                AttestationCampaignScope.ORGANIZATION, null, null, null, due))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsDueNotAfterOpen() {
        assertThatThrownBy(() -> new CreateAttestationCampaignCommand(org, creator, "n", null,
                AttestationCampaignScope.ORGANIZATION, null, null, due, open))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsDatasourceScopeWithoutDatasource() {
        assertThatThrownBy(() -> new CreateAttestationCampaignCommand(org, creator, "n", null,
                AttestationCampaignScope.DATASOURCE, null, null, open, due))
                .isInstanceOf(IllegalAttestationScopeException.class);
    }

    @Test
    void rejectsOrganizationScopeWithDatasource() {
        assertThatThrownBy(() -> new CreateAttestationCampaignCommand(org, creator, "n", null,
                AttestationCampaignScope.ORGANIZATION, datasource, null, open, due))
                .isInstanceOf(IllegalAttestationScopeException.class);
    }
}
