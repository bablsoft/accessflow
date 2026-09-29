package com.bablsoft.accessflow.attestation.internal;

import com.bablsoft.accessflow.attestation.api.AttestationCampaignNotFoundException;
import com.bablsoft.accessflow.attestation.api.AttestationItemDecision;
import com.bablsoft.accessflow.attestation.internal.config.AttestationProperties;
import com.bablsoft.accessflow.attestation.internal.persistence.entity.AttestationCampaignEntity;
import com.bablsoft.accessflow.attestation.internal.persistence.entity.AttestationItemEntity;
import com.bablsoft.accessflow.attestation.internal.persistence.repo.AttestationCampaignRepository;
import com.bablsoft.accessflow.attestation.internal.persistence.repo.AttestationItemRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultAttestationEvidenceExportServiceTest {

    @Mock AttestationCampaignRepository campaignRepository;
    @Mock AttestationItemRepository itemRepository;

    private final UUID campaignId = UUID.randomUUID();
    private final UUID orgId = UUID.randomUUID();

    private DefaultAttestationEvidenceExportService service(int maxRows) {
        return new DefaultAttestationEvidenceExportService(campaignRepository, itemRepository,
                new AttestationProperties(null, null, maxRows));
    }

    private AttestationCampaignEntity campaign() {
        var c = new AttestationCampaignEntity();
        c.setId(campaignId);
        c.setOrganizationId(orgId);
        c.setName("Q3 review");
        return c;
    }

    private AttestationItemEntity item(String email) {
        var i = new AttestationItemEntity();
        i.setId(UUID.randomUUID());
        i.setCampaignId(campaignId);
        i.setDatasourceName("Production");
        i.setSubjectUserEmail(email);
        i.setDecision(AttestationItemDecision.CERTIFIED);
        return i;
    }

    @Test
    void exportProducesCsvWithHeaderAndRows() {
        when(campaignRepository.findByIdAndOrganizationId(campaignId, orgId))
                .thenReturn(Optional.of(campaign()));
        when(itemRepository.findByCampaignIdOrderByCreatedAtAsc(campaignId))
                .thenReturn(List.of(item("alice@example.com"), item("bob@example.com")));

        var export = service(50_000).export(campaignId, orgId);
        var csv = new String(export.content(), StandardCharsets.UTF_8);

        assertThat(export.rowCount()).isEqualTo(2);
        assertThat(export.truncated()).isFalse();
        assertThat(export.filename()).contains(campaignId.toString());
        assertThat(csv).contains("subject_email").contains("alice@example.com")
                .contains("bob@example.com");
    }

    @Test
    void exportCarriesTheConfiguredAndEffectiveRowLimit() {
        when(campaignRepository.findByIdAndOrganizationId(campaignId, orgId))
                .thenReturn(Optional.of(campaign()));
        var limited = item("alice@example.com");
        limited.setRowLimitOverride(5_000);
        limited.setEffectiveRowLimit(1_000);
        limited.setRowLimitSource("datasource_cap");
        when(itemRepository.findByCampaignIdOrderByCreatedAtAsc(campaignId))
                .thenReturn(List.of(limited, item("bob@example.com")));

        var csv = new String(service(50_000).export(campaignId, orgId).content(),
                StandardCharsets.UTF_8);
        var lines = csv.split("\r\n");

        var header = List.of(lines[0].split(",", -1));
        var alice = List.of(lines[1].split(",", -1));
        var bob = List.of(lines[2].split(",", -1));
        int override = header.indexOf("row_limit_override");
        assertThat(header.get(override + 1)).isEqualTo("effective_row_limit");
        assertThat(header.get(override + 2)).isEqualTo("row_limit_source");
        assertThat(alice.subList(override, override + 3))
                .containsExactly("5000", "1000", "datasource_cap");
        // An item snapshotted before the evidence existed exports empty cells, not zero.
        assertThat(bob.subList(override, override + 3)).containsExactly("", "", "");
    }

    @Test
    void exportTruncatesBeyondCap() {
        when(campaignRepository.findByIdAndOrganizationId(campaignId, orgId))
                .thenReturn(Optional.of(campaign()));
        when(itemRepository.findByCampaignIdOrderByCreatedAtAsc(campaignId))
                .thenReturn(List.of(item("a@x.com"), item("b@x.com"), item("c@x.com")));

        var export = service(2).export(campaignId, orgId);

        assertThat(export.truncated()).isTrue();
        assertThat(export.rowCount()).isEqualTo(2);
    }

    @Test
    void exportThrowsWhenCampaignMissing() {
        lenient().when(itemRepository.findByCampaignIdOrderByCreatedAtAsc(campaignId))
                .thenReturn(List.of());
        when(campaignRepository.findByIdAndOrganizationId(campaignId, orgId))
                .thenReturn(Optional.empty());
        assertThatThrownBy(() -> service(50_000).export(campaignId, orgId))
                .isInstanceOf(AttestationCampaignNotFoundException.class);
    }
}
