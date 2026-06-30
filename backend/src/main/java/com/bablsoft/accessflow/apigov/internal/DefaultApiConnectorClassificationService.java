package com.bablsoft.accessflow.apigov.internal;

import com.bablsoft.accessflow.apigov.api.ApiConnectorClassificationAdminService;
import com.bablsoft.accessflow.apigov.api.ApiConnectorClassificationDerivationView;
import com.bablsoft.accessflow.apigov.api.ApiConnectorClassificationDerivationView.MaskingSuggestion;
import com.bablsoft.accessflow.apigov.api.ApiConnectorClassificationDerivationView.ReviewPosture;
import com.bablsoft.accessflow.apigov.api.ApiConnectorClassificationTagNotFoundException;
import com.bablsoft.accessflow.apigov.api.ApiConnectorClassificationTagView;
import com.bablsoft.accessflow.apigov.api.ApiConnectorMaskingAdminService;
import com.bablsoft.accessflow.apigov.api.ApiConnectorNotFoundException;
import com.bablsoft.accessflow.apigov.api.ApiMaskingMatcherType;
import com.bablsoft.accessflow.apigov.api.CreateApiConnectorClassificationTagCommand;
import com.bablsoft.accessflow.apigov.api.CreateApiConnectorMaskingPolicyCommand;
import com.bablsoft.accessflow.apigov.api.IllegalApiConnectorClassificationTagException;
import com.bablsoft.accessflow.apigov.internal.persistence.entity.ApiConnectorClassificationTagEntity;
import com.bablsoft.accessflow.apigov.internal.persistence.entity.ApiConnectorMaskingPolicyEntity;
import com.bablsoft.accessflow.apigov.internal.persistence.repo.ApiConnectorClassificationTagRepository;
import com.bablsoft.accessflow.apigov.internal.persistence.repo.ApiConnectorMaskingPolicyRepository;
import com.bablsoft.accessflow.apigov.internal.persistence.repo.ApiConnectorRepository;
import com.bablsoft.accessflow.core.api.DataClassification;
import lombok.RequiredArgsConstructor;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
@RequiredArgsConstructor
class DefaultApiConnectorClassificationService implements ApiConnectorClassificationAdminService {

    private final ApiConnectorClassificationTagRepository tagRepository;
    private final ApiConnectorMaskingPolicyRepository maskingPolicyRepository;
    private final ApiConnectorMaskingAdminService maskingAdminService;
    private final ApiConnectorRepository connectorRepository;
    private final MessageSource messageSource;

    @Override
    @Transactional(readOnly = true)
    public List<ApiConnectorClassificationTagView> listForConnector(UUID connectorId, UUID organizationId) {
        requireConnectorInOrganization(connectorId, organizationId);
        return tagRepository
                .findAllByOrganizationIdAndConnectorIdOrderByCreatedAt(organizationId, connectorId)
                .stream()
                .map(this::toView)
                .toList();
    }

    @Override
    @Transactional
    public List<ApiConnectorClassificationTagView> create(UUID connectorId, UUID organizationId,
                                                          CreateApiConnectorClassificationTagCommand command) {
        requireConnectorInOrganization(connectorId, organizationId);
        var matcherType = requireMatcherType(command.matcherType());
        var operationId = requireOperationFor(matcherType, command.operationId());
        var fieldRef = requireFieldRef(command.fieldRef());
        var classifications = requireClassifications(command.classifications());
        var note = normalize(command.note());
        var applyMasking = command.applyMasking() == null || command.applyMasking();

        var created = new ArrayList<ApiConnectorClassificationTagView>(classifications.size());
        for (var classification : classifications) {
            if (isDuplicate(organizationId, connectorId, operationId, fieldRef, classification)) {
                throw new IllegalApiConnectorClassificationTagException(
                        msg("error.api_classification_tag_duplicate"));
            }
            var entity = new ApiConnectorClassificationTagEntity();
            entity.setId(UUID.randomUUID());
            entity.setOrganizationId(organizationId);
            entity.setConnectorId(connectorId);
            entity.setOperationId(operationId);
            entity.setFieldRef(fieldRef);
            entity.setMatcherType(matcherType);
            entity.setClassification(classification);
            entity.setNote(note);
            var saved = tagRepository.save(entity);
            if (applyMasking) {
                deriveMasking(connectorId, organizationId, matcherType, operationId, fieldRef, classification);
            }
            created.add(toView(saved));
        }
        return created;
    }

    @Override
    @Transactional
    public void delete(UUID tagId, UUID connectorId, UUID organizationId) {
        requireConnectorInOrganization(connectorId, organizationId);
        var entity = tagRepository.findByIdAndOrganizationIdAndConnectorId(tagId, organizationId, connectorId)
                .orElseThrow(() -> new ApiConnectorClassificationTagNotFoundException(tagId));
        tagRepository.delete(entity);
    }

    @Override
    @Transactional(readOnly = true)
    public ApiConnectorClassificationDerivationView previewDerivation(UUID connectorId, UUID organizationId) {
        requireConnectorInOrganization(connectorId, organizationId);
        var tags = tagRepository.findAllByOrganizationIdAndConnectorIdOrderByCreatedAt(organizationId, connectorId);

        var aiReview = false;
        var humanApproval = false;
        var minApprovals = 0;
        var drivenBy = new LinkedHashSet<DataClassification>();
        for (var tag : tags) {
            var def = ApiConnectorClassificationDefaults.forClassification(tag.getClassification());
            if (def == null) {
                continue;
            }
            aiReview = aiReview || def.requiresAiReview();
            humanApproval = humanApproval || def.requiresHumanApproval();
            minApprovals = Math.max(minApprovals, def.minApprovals());
            drivenBy.add(tag.getClassification());
        }

        var enabledPolicies = maskingPolicyRepository
                .findAllByOrganizationIdAndConnectorIdAndEnabledTrue(organizationId, connectorId);
        var suggestions = new ArrayList<MaskingSuggestion>();
        for (var tag : tags) {
            var def = ApiConnectorClassificationDefaults.forClassification(tag.getClassification());
            if (def == null || def.maskingStrategy() == null) {
                continue;
            }
            var applied = enabledPolicies.stream()
                    .anyMatch(p -> covers(p, tag.getMatcherType(), tag.getOperationId(), tag.getFieldRef()));
            suggestions.add(new MaskingSuggestion(tag.getMatcherType(), tag.getOperationId(),
                    tag.getFieldRef(), tag.getClassification(), def.maskingStrategy(), def.maskingParams(),
                    applied));
        }

        var drivenList = drivenBy.stream().sorted().toList();
        return new ApiConnectorClassificationDerivationView(
                new ReviewPosture(aiReview, humanApproval, minApprovals, drivenList), suggestions);
    }

    private void deriveMasking(UUID connectorId, UUID organizationId, ApiMaskingMatcherType matcherType,
                               String operationId, String fieldRef, DataClassification classification) {
        var def = ApiConnectorClassificationDefaults.forClassification(classification);
        if (def == null || def.maskingStrategy() == null) {
            return;
        }
        var alreadyCovered = maskingPolicyRepository
                .findAllByOrganizationIdAndConnectorIdAndEnabledTrue(organizationId, connectorId)
                .stream()
                .anyMatch(p -> covers(p, matcherType, operationId, fieldRef));
        if (alreadyCovered) {
            return;
        }
        maskingAdminService.create(connectorId, organizationId, new CreateApiConnectorMaskingPolicyCommand(
                matcherType, operationId, fieldRef, def.maskingStrategy(), def.maskingParams(),
                List.of(), List.of(), List.of(), true));
    }

    private static boolean covers(ApiConnectorMaskingPolicyEntity policy, ApiMaskingMatcherType matcherType,
                                  String operationId, String fieldRef) {
        return policy.getMatcherType() == matcherType
                && Objects.equals(normalize(policy.getOperationId()), normalize(operationId))
                && policy.getFieldRef() != null
                && policy.getFieldRef().equalsIgnoreCase(fieldRef);
    }

    private boolean isDuplicate(UUID organizationId, UUID connectorId, String operationId,
                                String fieldRef, DataClassification classification) {
        if (operationId == null) {
            return tagRepository
                    .existsByOrganizationIdAndConnectorIdAndOperationIdIsNullAndFieldRefAndClassification(
                            organizationId, connectorId, fieldRef, classification);
        }
        return tagRepository
                .existsByOrganizationIdAndConnectorIdAndOperationIdAndFieldRefAndClassification(
                        organizationId, connectorId, operationId, fieldRef, classification);
    }

    private void requireConnectorInOrganization(UUID connectorId, UUID organizationId) {
        connectorRepository.findByIdAndOrganizationId(connectorId, organizationId)
                .orElseThrow(() -> new ApiConnectorNotFoundException(connectorId));
    }

    private ApiMaskingMatcherType requireMatcherType(ApiMaskingMatcherType matcherType) {
        if (matcherType == null) {
            throw new IllegalApiConnectorClassificationTagException(
                    msg("error.api_classification_tag_matcher_required"));
        }
        return matcherType;
    }

    private String requireOperationFor(ApiMaskingMatcherType matcherType, String operationId) {
        var trimmed = operationId == null ? null : operationId.trim();
        if (matcherType == ApiMaskingMatcherType.SCHEMA_FIELD && (trimmed == null || trimmed.isBlank())) {
            throw new IllegalApiConnectorClassificationTagException(
                    msg("error.api_classification_tag_operation_required"));
        }
        return trimmed == null || trimmed.isBlank() ? null : trimmed;
    }

    private String requireFieldRef(String fieldRef) {
        if (fieldRef == null || fieldRef.isBlank()) {
            throw new IllegalApiConnectorClassificationTagException(
                    msg("error.api_classification_tag_field_required"));
        }
        return fieldRef.trim();
    }

    private List<DataClassification> requireClassifications(List<DataClassification> classifications) {
        var distinct = new ArrayList<DataClassification>();
        if (classifications != null) {
            for (var classification : classifications) {
                if (classification != null && !distinct.contains(classification)) {
                    distinct.add(classification);
                }
            }
        }
        if (distinct.isEmpty()) {
            throw new IllegalApiConnectorClassificationTagException(
                    msg("error.api_classification_tag_classifications_required"));
        }
        return distinct;
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String msg(String key, Object... args) {
        return messageSource.getMessage(key, args, LocaleContextHolder.getLocale());
    }

    private ApiConnectorClassificationTagView toView(ApiConnectorClassificationTagEntity entity) {
        return new ApiConnectorClassificationTagView(
                entity.getId(),
                entity.getConnectorId(),
                entity.getOperationId(),
                entity.getFieldRef(),
                entity.getMatcherType(),
                entity.getClassification(),
                entity.getNote(),
                entity.getCreatedAt(),
                entity.getUpdatedAt());
    }
}
