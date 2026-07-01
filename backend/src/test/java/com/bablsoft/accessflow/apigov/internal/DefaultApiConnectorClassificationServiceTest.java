package com.bablsoft.accessflow.apigov.internal;

import com.bablsoft.accessflow.apigov.api.ApiConnectorClassificationTagNotFoundException;
import com.bablsoft.accessflow.apigov.api.ApiConnectorMaskingAdminService;
import com.bablsoft.accessflow.apigov.api.ApiConnectorNotFoundException;
import com.bablsoft.accessflow.apigov.api.ApiMaskingMatcherType;
import com.bablsoft.accessflow.apigov.api.CreateApiConnectorClassificationTagCommand;
import com.bablsoft.accessflow.apigov.api.CreateApiConnectorMaskingPolicyCommand;
import com.bablsoft.accessflow.apigov.api.IllegalApiConnectorClassificationTagException;
import com.bablsoft.accessflow.apigov.internal.persistence.entity.ApiConnectorClassificationTagEntity;
import com.bablsoft.accessflow.apigov.internal.persistence.entity.ApiConnectorEntity;
import com.bablsoft.accessflow.apigov.internal.persistence.repo.ApiConnectorClassificationTagRepository;
import com.bablsoft.accessflow.apigov.internal.persistence.repo.ApiConnectorMaskingPolicyRepository;
import com.bablsoft.accessflow.apigov.internal.persistence.repo.ApiConnectorRepository;
import com.bablsoft.accessflow.core.api.DataClassification;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DefaultApiConnectorClassificationServiceTest {

    @Mock ApiConnectorClassificationTagRepository tagRepository;
    @Mock ApiConnectorMaskingPolicyRepository maskingPolicyRepository;
    @Mock ApiConnectorMaskingAdminService maskingAdminService;
    @Mock ApiConnectorRepository connectorRepository;
    @Mock MessageSource messageSource;

    private DefaultApiConnectorClassificationService service;

    private final UUID orgId = UUID.randomUUID();
    private final UUID connectorId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new DefaultApiConnectorClassificationService(tagRepository, maskingPolicyRepository,
                maskingAdminService, connectorRepository, messageSource);
        when(messageSource.getMessage(any(), any(), any())).thenReturn("error");
        var connector = new ApiConnectorEntity();
        connector.setId(connectorId);
        connector.setOrganizationId(orgId);
        when(connectorRepository.findByIdAndOrganizationId(connectorId, orgId))
                .thenReturn(Optional.of(connector));
        when(tagRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(maskingPolicyRepository.findAllByOrganizationIdAndConnectorIdAndEnabledTrue(orgId, connectorId))
                .thenReturn(List.of());
    }

    @Test
    void createTagsAndDerivesMasking() {
        var command = new CreateApiConnectorClassificationTagCommand(ApiMaskingMatcherType.JSON_PATH,
                null, "user.ssn", List.of(DataClassification.PII), "note", true);

        var created = service.create(connectorId, orgId, command);

        assertThat(created).hasSize(1);
        assertThat(created.getFirst().classification()).isEqualTo(DataClassification.PII);
        // PII derives a PARTIAL masking policy
        verify(maskingAdminService).create(any(), any(), any(CreateApiConnectorMaskingPolicyCommand.class));
    }

    @Test
    void createWithoutApplyMaskingSkipsDerivation() {
        var command = new CreateApiConnectorClassificationTagCommand(ApiMaskingMatcherType.JSON_PATH,
                null, "user.ssn", List.of(DataClassification.PII), null, false);

        service.create(connectorId, orgId, command);

        verify(maskingAdminService, never()).create(any(), any(), any());
    }

    @Test
    void createRejectsDuplicate() {
        when(tagRepository
                .existsByOrganizationIdAndConnectorIdAndOperationIdIsNullAndFieldRefAndClassification(
                        orgId, connectorId, "user.ssn", DataClassification.PII))
                .thenReturn(true);
        var command = new CreateApiConnectorClassificationTagCommand(ApiMaskingMatcherType.JSON_PATH,
                null, "user.ssn", List.of(DataClassification.PII), null, false);

        assertThatThrownBy(() -> service.create(connectorId, orgId, command))
                .isInstanceOf(IllegalApiConnectorClassificationTagException.class);
    }

    @Test
    void createRejectsSchemaFieldWithoutOperation() {
        var command = new CreateApiConnectorClassificationTagCommand(ApiMaskingMatcherType.SCHEMA_FIELD,
                null, "email", List.of(DataClassification.PII), null, false);

        assertThatThrownBy(() -> service.create(connectorId, orgId, command))
                .isInstanceOf(IllegalApiConnectorClassificationTagException.class);
    }

    @Test
    void createRejectsBlankFieldRef() {
        var command = new CreateApiConnectorClassificationTagCommand(ApiMaskingMatcherType.JSON_PATH,
                null, "  ", List.of(DataClassification.PII), null, false);

        assertThatThrownBy(() -> service.create(connectorId, orgId, command))
                .isInstanceOf(IllegalApiConnectorClassificationTagException.class);
    }

    @Test
    void createRejectsNoClassifications() {
        var command = new CreateApiConnectorClassificationTagCommand(ApiMaskingMatcherType.JSON_PATH,
                null, "a", List.of(), null, false);

        assertThatThrownBy(() -> service.create(connectorId, orgId, command))
                .isInstanceOf(IllegalApiConnectorClassificationTagException.class);
    }

    @Test
    void createRejectsConnectorFromOtherOrg() {
        when(connectorRepository.findByIdAndOrganizationId(connectorId, orgId)).thenReturn(Optional.empty());
        var command = new CreateApiConnectorClassificationTagCommand(ApiMaskingMatcherType.JSON_PATH,
                null, "a", List.of(DataClassification.PII), null, false);

        assertThatThrownBy(() -> service.create(connectorId, orgId, command))
                .isInstanceOf(ApiConnectorNotFoundException.class);
    }

    @Test
    void deleteRemovesTag() {
        var tagId = UUID.randomUUID();
        var entity = new ApiConnectorClassificationTagEntity();
        entity.setId(tagId);
        when(tagRepository.findByIdAndOrganizationIdAndConnectorId(tagId, orgId, connectorId))
                .thenReturn(Optional.of(entity));

        service.delete(tagId, connectorId, orgId);

        verify(tagRepository).delete(entity);
    }

    @Test
    void deleteRejectsMissingTag() {
        var tagId = UUID.randomUUID();
        when(tagRepository.findByIdAndOrganizationIdAndConnectorId(tagId, orgId, connectorId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.delete(tagId, connectorId, orgId))
                .isInstanceOf(ApiConnectorClassificationTagNotFoundException.class);
    }

    @Test
    void previewDerivationAggregatesPostureAndSuggestions() {
        var tag = new ApiConnectorClassificationTagEntity();
        tag.setMatcherType(ApiMaskingMatcherType.JSON_PATH);
        tag.setFieldRef("user.ssn");
        tag.setClassification(DataClassification.PCI);
        when(tagRepository.findAllByOrganizationIdAndConnectorIdOrderByCreatedAt(orgId, connectorId))
                .thenReturn(List.of(tag));

        var view = service.previewDerivation(connectorId, orgId);

        assertThat(view.suggestedReviewPosture().requiresHumanApproval()).isTrue();
        assertThat(view.suggestedReviewPosture().minApprovals()).isEqualTo(2);
        assertThat(view.suggestedReviewPosture().drivenBy()).contains(DataClassification.PCI);
        assertThat(view.maskingSuggestions()).hasSize(1);
        assertThat(view.maskingSuggestions().getFirst().alreadyApplied()).isFalse();
    }
}
