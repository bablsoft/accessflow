package com.bablsoft.accessflow.core.internal;

import com.bablsoft.accessflow.core.api.CreateDataClassificationTagCommand;
import com.bablsoft.accessflow.core.api.CreateMaskingPolicyCommand;
import com.bablsoft.accessflow.core.api.DataClassification;
import com.bablsoft.accessflow.core.api.DataClassificationTagNotFoundException;
import com.bablsoft.accessflow.core.api.DatasourceNotFoundException;
import com.bablsoft.accessflow.core.api.IllegalDataClassificationTagException;
import com.bablsoft.accessflow.core.api.MaskingPolicyAdminService;
import com.bablsoft.accessflow.core.api.MaskingStrategy;
import com.bablsoft.accessflow.core.internal.persistence.entity.DataClassificationTagEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.DatasourceEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.MaskingPolicyEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.OrganizationEntity;
import com.bablsoft.accessflow.core.internal.persistence.repo.DataClassificationTagRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.DatasourceRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.MaskingPolicyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.MessageSource;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DefaultDataClassificationServiceTest {

    @Mock DataClassificationTagRepository tagRepository;
    @Mock DatasourceRepository datasourceRepository;
    @Mock MaskingPolicyAdminService maskingPolicyAdminService;
    @Mock MaskingPolicyRepository maskingPolicyRepository;
    @Mock MessageSource messageSource;

    private DefaultDataClassificationService service;

    private final UUID orgId = UUID.randomUUID();
    private final UUID datasourceId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new DefaultDataClassificationService(tagRepository, datasourceRepository,
                maskingPolicyAdminService, maskingPolicyRepository, messageSource);
        when(messageSource.getMessage(any(), any(), any())).thenReturn("error");
        when(datasourceRepository.findById(datasourceId)).thenReturn(Optional.of(datasource(orgId, "Prod DB")));
        when(tagRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void listMapsEntitiesToViews() {
        when(tagRepository
                .findAllByOrganizationIdAndDatasourceIdOrderByTableNameAscColumnNameAscClassificationAsc(
                        orgId, datasourceId))
                .thenReturn(List.of(tag("users", "email", DataClassification.PII)));

        var result = service.listForDatasource(datasourceId, orgId);

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().tableName()).isEqualTo("users");
        assertThat(result.getFirst().columnName()).isEqualTo("email");
        assertThat(result.getFirst().classification()).isEqualTo(DataClassification.PII);
    }

    @Test
    void listRejectsDatasourceFromOtherOrg() {
        when(datasourceRepository.findById(datasourceId))
                .thenReturn(Optional.of(datasource(UUID.randomUUID(), "Other")));

        assertThatThrownBy(() -> service.listForDatasource(datasourceId, orgId))
                .isInstanceOf(DatasourceNotFoundException.class);
    }

    @Test
    void findByDatasourceDoesNotValidateOrganization() {
        when(tagRepository
                .findAllByOrganizationIdAndDatasourceIdOrderByTableNameAscColumnNameAscClassificationAsc(
                        orgId, datasourceId))
                .thenReturn(List.of(tag("users", null, DataClassification.PCI)));

        var result = service.findByDatasource(datasourceId, orgId);

        assertThat(result).hasSize(1);
        verify(datasourceRepository, never()).findById(any());
    }

    @Test
    void createColumnTagDerivesMaskingPolicy() {
        var command = new CreateDataClassificationTagCommand("users", "email",
                List.of(DataClassification.PII), "pii column", null);

        var created = service.create(datasourceId, orgId, command);

        assertThat(created).hasSize(1);
        var captor = ArgumentCaptor.forClass(CreateMaskingPolicyCommand.class);
        verify(maskingPolicyAdminService).create(eq(datasourceId), eq(orgId), captor.capture());
        var maskingCmd = captor.getValue();
        assertThat(maskingCmd.columnRef()).isEqualTo("users.email");
        assertThat(maskingCmd.strategy()).isEqualTo(MaskingStrategy.PARTIAL);
        assertThat(maskingCmd.strategyParams()).containsEntry("visible_suffix", "4");
    }

    @Test
    void createColumnTagSkipsMaskingWhenPolicyAlreadyEnabled() {
        var existing = new MaskingPolicyEntity();
        existing.setColumnRef("USERS.EMAIL");
        when(maskingPolicyRepository.findAllByOrganizationIdAndDatasourceIdAndEnabledTrue(orgId, datasourceId))
                .thenReturn(List.of(existing));
        var command = new CreateDataClassificationTagCommand("users", "email",
                List.of(DataClassification.PII), null, true);

        service.create(datasourceId, orgId, command);

        verify(maskingPolicyAdminService, never()).create(any(), any(), any());
    }

    @Test
    void createTableLevelTagDerivesNoMasking() {
        var command = new CreateDataClassificationTagCommand("orders", null,
                List.of(DataClassification.PCI), null, true);

        service.create(datasourceId, orgId, command);

        verify(maskingPolicyAdminService, never()).create(any(), any(), any());
    }

    @Test
    void createWithApplyMaskingFalseDerivesNoMasking() {
        var command = new CreateDataClassificationTagCommand("users", "email",
                List.of(DataClassification.PII), null, false);

        service.create(datasourceId, orgId, command);

        verify(maskingPolicyAdminService, never()).create(any(), any(), any());
    }

    @Test
    void createPersistsOneTagPerDistinctClassification() {
        var command = new CreateDataClassificationTagCommand("users", null,
                List.of(DataClassification.PII, DataClassification.GDPR, DataClassification.PII), null, false);

        var created = service.create(datasourceId, orgId, command);

        assertThat(created).hasSize(2);
        verify(tagRepository, org.mockito.Mockito.times(2)).save(any());
    }

    @Test
    void createRejectsBlankTable() {
        var command = new CreateDataClassificationTagCommand("  ", "email",
                List.of(DataClassification.PII), null, true);

        assertThatThrownBy(() -> service.create(datasourceId, orgId, command))
                .isInstanceOf(IllegalDataClassificationTagException.class);
        verify(tagRepository, never()).save(any());
    }

    @Test
    void createRejectsEmptyClassifications() {
        var command = new CreateDataClassificationTagCommand("users", "email", List.of(), null, true);

        assertThatThrownBy(() -> service.create(datasourceId, orgId, command))
                .isInstanceOf(IllegalDataClassificationTagException.class);
        verify(tagRepository, never()).save(any());
    }

    @Test
    void createRejectsDuplicateTag() {
        when(tagRepository
                .existsByOrganizationIdAndDatasourceIdAndTableNameAndColumnNameAndClassification(
                        eq(orgId), eq(datasourceId), eq("users"), eq("email"), eq(DataClassification.PII)))
                .thenReturn(true);
        var command = new CreateDataClassificationTagCommand("users", "email",
                List.of(DataClassification.PII), null, true);

        assertThatThrownBy(() -> service.create(datasourceId, orgId, command))
                .isInstanceOf(IllegalDataClassificationTagException.class);
    }

    @Test
    void deleteRemovesTag() {
        var entity = tag("users", "email", DataClassification.PII);
        when(tagRepository.findByIdAndOrganizationId(entity.getId(), orgId)).thenReturn(Optional.of(entity));

        service.delete(entity.getId(), datasourceId, orgId);

        verify(tagRepository).delete(entity);
    }

    @Test
    void deleteRejectsTagFromDifferentDatasource() {
        var entity = tag("users", "email", DataClassification.PII);
        entity.setDatasourceId(UUID.randomUUID());
        when(tagRepository.findByIdAndOrganizationId(entity.getId(), orgId)).thenReturn(Optional.of(entity));

        assertThatThrownBy(() -> service.delete(entity.getId(), datasourceId, orgId))
                .isInstanceOf(DataClassificationTagNotFoundException.class);
        verify(tagRepository, never()).delete(any());
    }

    @Test
    void deleteRejectsMissingTag() {
        var id = UUID.randomUUID();
        when(tagRepository.findByIdAndOrganizationId(id, orgId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.delete(id, datasourceId, orgId))
                .isInstanceOf(DataClassificationTagNotFoundException.class);
    }

    @Test
    void previewAggregatesReviewPostureAndMaskingSuggestions() {
        when(tagRepository
                .findAllByOrganizationIdAndDatasourceIdOrderByTableNameAscColumnNameAscClassificationAsc(
                        orgId, datasourceId))
                .thenReturn(List.of(
                        tag("users", "email", DataClassification.PII),
                        tag("orders", null, DataClassification.PCI)));

        var preview = service.previewDerivation(datasourceId, orgId);

        var posture = preview.suggestedReviewPosture();
        assertThat(posture.requiresAiReview()).isTrue();
        assertThat(posture.requiresHumanApproval()).isTrue();
        assertThat(posture.minApprovals()).isEqualTo(2); // PCI requires 2
        assertThat(posture.drivenBy()).containsExactly(DataClassification.PII, DataClassification.PCI);
        // Only the column-level PII tag yields a masking suggestion.
        assertThat(preview.maskingSuggestions()).hasSize(1);
        var suggestion = preview.maskingSuggestions().getFirst();
        assertThat(suggestion.columnRef()).isEqualTo("users.email");
        assertThat(suggestion.suggestedStrategy()).isEqualTo(MaskingStrategy.PARTIAL);
        assertThat(suggestion.alreadyApplied()).isFalse();
    }

    @Test
    void previewFlagsMaskingSuggestionAsApplied() {
        var existing = new MaskingPolicyEntity();
        existing.setColumnRef("users.email");
        when(maskingPolicyRepository.findAllByOrganizationIdAndDatasourceIdAndEnabledTrue(orgId, datasourceId))
                .thenReturn(List.of(existing));
        when(tagRepository
                .findAllByOrganizationIdAndDatasourceIdOrderByTableNameAscColumnNameAscClassificationAsc(
                        orgId, datasourceId))
                .thenReturn(List.of(tag("users", "email", DataClassification.PII)));

        var preview = service.previewDerivation(datasourceId, orgId);

        assertThat(preview.maskingSuggestions().getFirst().alreadyApplied()).isTrue();
    }

    @Test
    void listForOrganizationJoinsDatasourceName() {
        when(tagRepository.findAllByOrganizationIdOrderByCreatedAtAsc(orgId))
                .thenReturn(List.of(tag("users", "email", DataClassification.PII)));
        when(datasourceRepository.findAllByOrganization_Id(orgId))
                .thenReturn(List.of(datasource(orgId, "Prod DB")));

        var result = service.listForOrganization(orgId);

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().datasourceName()).isEqualTo("Prod DB");
        assertThat(result.getFirst().classification()).isEqualTo(DataClassification.PII);
    }

    @Test
    void listForOrganizationReturnsEmptyWhenNoTags() {
        when(tagRepository.findAllByOrganizationIdOrderByCreatedAtAsc(orgId)).thenReturn(List.of());

        assertThat(service.listForOrganization(orgId)).isEmpty();
        verify(datasourceRepository, never()).findAllByOrganization_Id(any());
    }

    private DatasourceEntity datasource(UUID ownerOrgId, String name) {
        var org = new OrganizationEntity();
        org.setId(ownerOrgId);
        var ds = new DatasourceEntity();
        ds.setId(datasourceId);
        ds.setName(name);
        ds.setOrganization(org);
        return ds;
    }

    private DataClassificationTagEntity tag(String table, String column, DataClassification classification) {
        var entity = new DataClassificationTagEntity();
        entity.setId(UUID.randomUUID());
        entity.setOrganizationId(orgId);
        entity.setDatasourceId(datasourceId);
        entity.setTableName(table);
        entity.setColumnName(column);
        entity.setClassification(classification);
        return entity;
    }
}
