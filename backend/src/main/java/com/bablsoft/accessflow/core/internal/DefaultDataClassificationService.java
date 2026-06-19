package com.bablsoft.accessflow.core.internal;

import com.bablsoft.accessflow.core.api.CreateDataClassificationTagCommand;
import com.bablsoft.accessflow.core.api.CreateMaskingPolicyCommand;
import com.bablsoft.accessflow.core.api.DataClassification;
import com.bablsoft.accessflow.core.api.DataClassificationAdminService;
import com.bablsoft.accessflow.core.api.DataClassificationDerivationView;
import com.bablsoft.accessflow.core.api.DataClassificationDerivationView.MaskingSuggestion;
import com.bablsoft.accessflow.core.api.DataClassificationDerivationView.ReviewPosture;
import com.bablsoft.accessflow.core.api.DataClassificationQueryService;
import com.bablsoft.accessflow.core.api.DataClassificationTagNotFoundException;
import com.bablsoft.accessflow.core.api.DataClassificationTagView;
import com.bablsoft.accessflow.core.api.DatasourceNotFoundException;
import com.bablsoft.accessflow.core.api.IllegalDataClassificationTagException;
import com.bablsoft.accessflow.core.api.MaskingPolicyAdminService;
import com.bablsoft.accessflow.core.api.OrganizationDataClassificationView;
import com.bablsoft.accessflow.core.internal.persistence.entity.DataClassificationTagEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.DatasourceEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.MaskingPolicyEntity;
import com.bablsoft.accessflow.core.internal.persistence.repo.DataClassificationTagRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.DatasourceRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.MaskingPolicyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
class DefaultDataClassificationService
        implements DataClassificationAdminService, DataClassificationQueryService {

    private final DataClassificationTagRepository tagRepository;
    private final DatasourceRepository datasourceRepository;
    private final MaskingPolicyAdminService maskingPolicyAdminService;
    private final MaskingPolicyRepository maskingPolicyRepository;
    private final MessageSource messageSource;

    @Override
    @Transactional(readOnly = true)
    public List<DataClassificationTagView> listForDatasource(UUID datasourceId, UUID organizationId) {
        requireDatasourceInOrganization(datasourceId, organizationId);
        return loadTags(datasourceId, organizationId).stream().map(this::toView).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<DataClassificationTagView> findByDatasource(UUID datasourceId, UUID organizationId) {
        return loadTags(datasourceId, organizationId).stream().map(this::toView).toList();
    }

    @Override
    @Transactional
    public List<DataClassificationTagView> create(UUID datasourceId, UUID organizationId,
                                                  CreateDataClassificationTagCommand command) {
        requireDatasourceInOrganization(datasourceId, organizationId);
        var tableName = requireTableName(command.tableName());
        var columnName = normalize(command.columnName());
        var classifications = requireClassifications(command.classifications());
        var note = normalize(command.note());
        var applyMasking = command.applyMasking() == null || command.applyMasking();

        var created = new ArrayList<DataClassificationTagView>(classifications.size());
        for (var classification : classifications) {
            if (tagRepository
                    .existsByOrganizationIdAndDatasourceIdAndTableNameAndColumnNameAndClassification(
                            organizationId, datasourceId, tableName, columnName, classification)) {
                throw new IllegalDataClassificationTagException(
                        msg("error.data_classification_tag_duplicate"));
            }
            var entity = new DataClassificationTagEntity();
            entity.setId(UUID.randomUUID());
            entity.setOrganizationId(organizationId);
            entity.setDatasourceId(datasourceId);
            entity.setTableName(tableName);
            entity.setColumnName(columnName);
            entity.setClassification(classification);
            entity.setNote(note);
            var saved = tagRepository.save(entity);
            if (columnName != null && applyMasking) {
                deriveMasking(datasourceId, organizationId, tableName, columnName, classification);
            }
            created.add(toView(saved));
        }
        return created;
    }

    @Override
    @Transactional
    public void delete(UUID tagId, UUID datasourceId, UUID organizationId) {
        var entity = loadInScope(tagId, datasourceId, organizationId);
        tagRepository.delete(entity);
    }

    @Override
    @Transactional(readOnly = true)
    public DataClassificationDerivationView previewDerivation(UUID datasourceId, UUID organizationId) {
        requireDatasourceInOrganization(datasourceId, organizationId);
        var tags = loadTags(datasourceId, organizationId);

        var aiReview = false;
        var humanApproval = false;
        var minApprovals = 0;
        var drivenBy = new LinkedHashSet<DataClassification>();
        for (var tag : tags) {
            var def = DataClassificationDefaults.forClassification(tag.getClassification());
            if (def == null) {
                continue;
            }
            aiReview = aiReview || def.requiresAiReview();
            humanApproval = humanApproval || def.requiresHumanApproval();
            minApprovals = Math.max(minApprovals, def.minApprovals());
            drivenBy.add(tag.getClassification());
        }

        var enabledRefs = maskingPolicyRepository
                .findAllByOrganizationIdAndDatasourceIdAndEnabledTrue(organizationId, datasourceId)
                .stream()
                .map(MaskingPolicyEntity::getColumnRef)
                .filter(Objects::nonNull)
                .toList();
        var suggestions = new ArrayList<MaskingSuggestion>();
        for (var tag : tags) {
            if (tag.getColumnName() == null) {
                continue;
            }
            var def = DataClassificationDefaults.forClassification(tag.getClassification());
            if (def == null || def.maskingStrategy() == null) {
                continue;
            }
            var columnRef = columnRef(tag.getTableName(), tag.getColumnName());
            var applied = enabledRefs.stream().anyMatch(ref -> ref.equalsIgnoreCase(columnRef));
            suggestions.add(new MaskingSuggestion(columnRef, tag.getClassification(),
                    def.maskingStrategy(), def.maskingParams(), applied));
        }

        var drivenList = drivenBy.stream().sorted().toList();
        return new DataClassificationDerivationView(
                new ReviewPosture(aiReview, humanApproval, minApprovals, drivenList), suggestions);
    }

    @Override
    @Transactional(readOnly = true)
    public List<OrganizationDataClassificationView> listForOrganization(UUID organizationId) {
        var tags = tagRepository.findAllByOrganizationIdOrderByCreatedAtAsc(organizationId);
        if (tags.isEmpty()) {
            return List.of();
        }
        Map<UUID, String> names = datasourceRepository.findAllByOrganization_Id(organizationId).stream()
                .collect(Collectors.toMap(DatasourceEntity::getId, DatasourceEntity::getName,
                        (a, b) -> a));
        return tags.stream()
                .map(tag -> new OrganizationDataClassificationView(
                        tag.getId(), tag.getDatasourceId(), names.get(tag.getDatasourceId()),
                        tag.getTableName(), tag.getColumnName(), tag.getClassification(),
                        tag.getNote(), tag.getCreatedAt(), tag.getUpdatedAt()))
                .toList();
    }

    private void deriveMasking(UUID datasourceId, UUID organizationId, String tableName,
                               String columnName, DataClassification classification) {
        var def = DataClassificationDefaults.forClassification(classification);
        if (def == null || def.maskingStrategy() == null) {
            return;
        }
        var columnRef = columnRef(tableName, columnName);
        var alreadyCovered = maskingPolicyRepository
                .findAllByOrganizationIdAndDatasourceIdAndEnabledTrue(organizationId, datasourceId)
                .stream()
                .map(MaskingPolicyEntity::getColumnRef)
                .filter(Objects::nonNull)
                .anyMatch(ref -> ref.equalsIgnoreCase(columnRef));
        if (alreadyCovered) {
            return;
        }
        maskingPolicyAdminService.create(datasourceId, organizationId, new CreateMaskingPolicyCommand(
                columnRef, def.maskingStrategy(), def.maskingParams(),
                List.of(), List.of(), List.of(), true));
    }

    private List<DataClassificationTagEntity> loadTags(UUID datasourceId, UUID organizationId) {
        return tagRepository
                .findAllByOrganizationIdAndDatasourceIdOrderByTableNameAscColumnNameAscClassificationAsc(
                        organizationId, datasourceId);
    }

    private DataClassificationTagEntity loadInScope(UUID tagId, UUID datasourceId, UUID organizationId) {
        requireDatasourceInOrganization(datasourceId, organizationId);
        var entity = tagRepository.findByIdAndOrganizationId(tagId, organizationId)
                .orElseThrow(() -> new DataClassificationTagNotFoundException(tagId));
        if (!entity.getDatasourceId().equals(datasourceId)) {
            throw new DataClassificationTagNotFoundException(tagId);
        }
        return entity;
    }

    private void requireDatasourceInOrganization(UUID datasourceId, UUID organizationId) {
        var datasource = datasourceRepository.findById(datasourceId)
                .orElseThrow(() -> new DatasourceNotFoundException(datasourceId));
        if (!datasource.getOrganization().getId().equals(organizationId)) {
            throw new DatasourceNotFoundException(datasourceId);
        }
    }

    private String requireTableName(String tableName) {
        if (tableName == null || tableName.isBlank()) {
            throw new IllegalDataClassificationTagException(
                    msg("error.data_classification_table_required"));
        }
        return tableName.trim();
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
            throw new IllegalDataClassificationTagException(
                    msg("error.data_classification_classifications_required"));
        }
        return distinct;
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String columnRef(String tableName, String columnName) {
        return tableName + "." + columnName;
    }

    private String msg(String key, Object... args) {
        return messageSource.getMessage(key, args, LocaleContextHolder.getLocale());
    }

    private DataClassificationTagView toView(DataClassificationTagEntity entity) {
        return new DataClassificationTagView(
                entity.getId(),
                entity.getDatasourceId(),
                entity.getTableName(),
                entity.getColumnName(),
                entity.getClassification(),
                entity.getNote(),
                entity.getCreatedAt(),
                entity.getUpdatedAt());
    }
}
