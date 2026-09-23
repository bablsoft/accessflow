package com.bablsoft.accessflow.schemachange.internal;

import com.bablsoft.accessflow.audit.api.AuditAction;
import com.bablsoft.accessflow.audit.api.AuditResourceType;
import com.bablsoft.accessflow.core.api.PageRequest;
import com.bablsoft.accessflow.core.api.PageResponse;
import com.bablsoft.accessflow.deploygov.api.DeploymentPipelineLookupService;
import com.bablsoft.accessflow.schemachange.api.CreateSchemaChangeSetCommand;
import com.bablsoft.accessflow.schemachange.api.SchemaChangePipelineNotFoundException;
import com.bablsoft.accessflow.schemachange.api.SchemaChangePromotionStatus;
import com.bablsoft.accessflow.schemachange.api.SchemaChangeSetArchivedException;
import com.bablsoft.accessflow.schemachange.api.SchemaChangeSetFrozenException;
import com.bablsoft.accessflow.schemachange.api.SchemaChangeSetListFilter;
import com.bablsoft.accessflow.schemachange.api.SchemaChangeSetNameConflictException;
import com.bablsoft.accessflow.schemachange.api.SchemaChangeSetNotFoundException;
import com.bablsoft.accessflow.schemachange.api.SchemaChangeSetService;
import com.bablsoft.accessflow.schemachange.api.SchemaChangeSetStatementInput;
import com.bablsoft.accessflow.schemachange.api.SchemaChangeSetStatementLimitException;
import com.bablsoft.accessflow.schemachange.api.SchemaChangeSetStatementView;
import com.bablsoft.accessflow.schemachange.api.SchemaChangeSetStatus;
import com.bablsoft.accessflow.schemachange.api.SchemaChangeSetStatusTransitionException;
import com.bablsoft.accessflow.schemachange.api.SchemaChangeSetView;
import com.bablsoft.accessflow.schemachange.api.SchemaChangeStatementFinding;
import com.bablsoft.accessflow.schemachange.api.UpdateSchemaChangeSetCommand;
import com.bablsoft.accessflow.schemachange.internal.config.SchemaChangeProperties;
import com.bablsoft.accessflow.schemachange.internal.persistence.entity.SchemaChangeSetEntity;
import com.bablsoft.accessflow.schemachange.internal.persistence.entity.SchemaChangeSetStatementEntity;
import com.bablsoft.accessflow.schemachange.internal.persistence.repo.SchemaChangeSetPromotionRepository;
import com.bablsoft.accessflow.schemachange.internal.persistence.repo.SchemaChangeSetRepository;
import com.bablsoft.accessflow.schemachange.internal.persistence.repo.SchemaChangeSetStatementRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Change-set authoring (#879, epic #870). Org-scoped throughout — {@link #require} collapses a
 * missing and a foreign-org id into the same 404. Statement replacement is delete-then-reinsert
 * through the bulk JPQL delete, the {@code request_group_items} precedent, which also sidesteps
 * the {@code (change_set_id, sequence_order)} reordering problem.
 */
@Service
@RequiredArgsConstructor
public class DefaultSchemaChangeSetService implements SchemaChangeSetService {

    /** V178's per-pipeline name constraint — the only violation translated to a 409. */
    static final String NAME_CONSTRAINT = "uq_schema_change_sets_org_pipeline_name";

    /** Every promotion state except {@code FAILED} / {@code CANCELLED}: any such row freezes the set. */
    static final Set<SchemaChangePromotionStatus> FREEZING_STATUSES = EnumSet.of(
            SchemaChangePromotionStatus.PENDING, SchemaChangePromotionStatus.IN_REVIEW,
            SchemaChangePromotionStatus.APPROVED, SchemaChangePromotionStatus.APPLIED,
            SchemaChangePromotionStatus.PARTIALLY_APPLIED);

    private final SchemaChangeSetRepository changeSetRepository;
    private final SchemaChangeSetStatementRepository statementRepository;
    private final SchemaChangeSetPromotionRepository promotionRepository;
    private final DeploymentPipelineLookupService pipelineLookupService;
    private final SchemaChangeStatementGate gate;
    private final SchemaChangeProperties properties;
    private final SchemaChangeAuditWriter auditWriter;

    @Override
    @Transactional(readOnly = true)
    public PageResponse<SchemaChangeSetView> list(UUID organizationId, SchemaChangeSetListFilter filter,
                                                  PageRequest pageRequest) {
        var page = changeSetRepository.findAll(
                SchemaChangeSetSpecifications.forFilter(organizationId, filter), toPageable(pageRequest));
        var ids = page.getContent().stream().map(SchemaChangeSetEntity::getId).toList();
        var statementsBySet = ids.isEmpty()
                ? Map.<UUID, List<SchemaChangeSetStatementEntity>>of()
                : statementRepository.findAllByChangeSet_IdInOrderBySequenceOrderAsc(ids).stream()
                        .collect(Collectors.groupingBy(s -> s.getChangeSet().getId()));
        return toPageResponse(page.map(e -> toView(e, statementsBySet.getOrDefault(e.getId(), List.of()), List.of())));
    }

    @Override
    @Transactional(readOnly = true)
    public SchemaChangeSetView get(UUID organizationId, UUID changeSetId) {
        return toView(require(organizationId, changeSetId));
    }

    @Override
    @Transactional
    public SchemaChangeSetView create(UUID organizationId, UUID actorId, CreateSchemaChangeSetCommand command) {
        if (pipelineLookupService.findPipeline(command.pipelineId(), organizationId).isEmpty()) {
            throw new SchemaChangePipelineNotFoundException(command.pipelineId());
        }
        enforceCap(command.statements());
        if (changeSetRepository.existsByOrganizationIdAndPipelineIdAndName(
                organizationId, command.pipelineId(), command.name())) {
            throw new SchemaChangeSetNameConflictException(command.pipelineId(), command.name());
        }
        var validated = gate.validate(organizationId, command.pipelineId(), command.statements());

        var entity = new SchemaChangeSetEntity();
        entity.setId(UUID.randomUUID());
        entity.setOrganizationId(organizationId);
        entity.setPipelineId(command.pipelineId());
        entity.setName(command.name());
        entity.setDescription(command.description());
        entity.setStatus(SchemaChangeSetStatus.DRAFT);
        entity.setCreatedBy(actorId);
        entity.setStatementsChecksum(checksumOf(validated));
        var saved = saveChangeSet(entity);
        var rows = insertStatements(saved, validated);
        var metadata = statementMetadata(saved, rows.size());
        audit(AuditAction.SCHEMA_CHANGE_SET_CREATED, saved, actorId, metadata);
        return toView(saved, rows, validated.warnings());
    }

    @Override
    @Transactional
    public SchemaChangeSetView update(UUID organizationId, UUID actorId, UUID changeSetId,
                                      UpdateSchemaChangeSetCommand command) {
        var entity = require(organizationId, changeSetId);
        var changed = new ArrayList<String>();
        if (command.name() != null && !command.name().equals(entity.getName())) {
            changed.add("name");
            if (changeSetRepository.existsByOrganizationIdAndPipelineIdAndName(
                    organizationId, entity.getPipelineId(), command.name())) {
                throw new SchemaChangeSetNameConflictException(entity.getPipelineId(), command.name());
            }
            entity.setName(command.name());
        }
        if (command.description() != null && !command.description().equals(entity.getDescription())) {
            changed.add("description");
            entity.setDescription(command.description());
        }
        if (command.status() != null && command.status() != entity.getStatus()) {
            if (command.status() != SchemaChangeSetStatus.ARCHIVED) {
                throw new SchemaChangeSetStatusTransitionException(changeSetId, entity.getStatus(), command.status());
            }
            changed.add("status");
            entity.setStatus(SchemaChangeSetStatus.ARCHIVED);
        }
        var saved = saveChangeSet(entity);
        if (!changed.isEmpty()) {
            var metadata = baseMetadata(saved);
            metadata.put("changed_fields", changed);
            metadata.put("status", saved.getStatus().name());
            audit(AuditAction.SCHEMA_CHANGE_SET_UPDATED, saved, actorId, metadata);
        }
        return toView(saved);
    }

    @Override
    @Transactional
    public SchemaChangeSetView replaceStatements(UUID organizationId, UUID actorId, UUID changeSetId,
                                                 List<SchemaChangeSetStatementInput> statements) {
        var entity = require(organizationId, changeSetId);
        if (entity.getStatus() == SchemaChangeSetStatus.ARCHIVED) {
            throw new SchemaChangeSetArchivedException(changeSetId);
        }
        requireNotFrozen(changeSetId);
        enforceCap(statements);
        var validated = gate.validate(organizationId, entity.getPipelineId(), statements);

        statementRepository.deleteAllByChangeSetId(changeSetId);
        var rows = insertStatements(entity, validated);
        entity.setStatementsChecksum(checksumOf(validated));
        var saved = changeSetRepository.saveAndFlush(entity);
        var metadata = statementMetadata(saved, rows.size());
        audit(AuditAction.SCHEMA_CHANGE_SET_STATEMENTS_REPLACED, saved, actorId, metadata);
        return toView(saved, rows, validated.warnings());
    }

    @Override
    @Transactional
    public void delete(UUID organizationId, UUID actorId, UUID changeSetId) {
        var entity = require(organizationId, changeSetId);
        requireNotFrozen(changeSetId);
        var metadata = baseMetadata(entity);
        changeSetRepository.delete(entity);
        // Flushed first so a delete the database refuses never leaves a DELETED row behind.
        changeSetRepository.flush();
        audit(AuditAction.SCHEMA_CHANGE_SET_DELETED, entity, actorId, metadata);
    }

    private static Map<String, Object> baseMetadata(SchemaChangeSetEntity entity) {
        var metadata = new HashMap<String, Object>();
        metadata.put("pipeline_id", entity.getPipelineId().toString());
        metadata.put("name", entity.getName());
        return metadata;
    }

    private static Map<String, Object> statementMetadata(SchemaChangeSetEntity entity, int statementCount) {
        var metadata = baseMetadata(entity);
        metadata.put("statement_count", statementCount);
        if (entity.getStatementsChecksum() != null) {
            metadata.put("statements_checksum", entity.getStatementsChecksum());
        }
        return metadata;
    }

    private void audit(AuditAction action, SchemaChangeSetEntity entity, UUID actorId, Map<String, Object> metadata) {
        auditWriter.record(action, AuditResourceType.SCHEMA_CHANGE_SET, entity.getId(),
                entity.getOrganizationId(), actorId, metadata, null, null);
    }

    private SchemaChangeSetEntity require(UUID organizationId, UUID changeSetId) {
        return changeSetRepository.findByIdAndOrganizationId(changeSetId, organizationId)
                .orElseThrow(() -> new SchemaChangeSetNotFoundException(changeSetId));
    }

    private void requireNotFrozen(UUID changeSetId) {
        if (promotionRepository.existsByChangeSet_IdAndStatusIn(changeSetId, FREEZING_STATUSES)) {
            throw new SchemaChangeSetFrozenException(changeSetId);
        }
    }

    private void enforceCap(List<SchemaChangeSetStatementInput> statements) {
        var actual = statements == null ? 0 : statements.size();
        if (actual > properties.maxStatements()) {
            throw new SchemaChangeSetStatementLimitException(properties.maxStatements(), actual);
        }
    }

    /**
     * The pre-check loses a race between two authors; the unique constraint does not. Only the
     * name constraint is translated — any other violation keeps its own identity. The catch is the
     * {@code DataIntegrityViolationException} superclass on purpose: Hibernate maps a PG 23505
     * raised through {@code saveAndFlush} to exactly that type, never {@code DuplicateKeyException}.
     */
    private SchemaChangeSetEntity saveChangeSet(SchemaChangeSetEntity entity) {
        try {
            return changeSetRepository.saveAndFlush(entity);
        } catch (DataIntegrityViolationException ex) {
            var cause = ex.getMostSpecificCause().getMessage();
            if (cause != null && cause.contains(NAME_CONSTRAINT)) {
                throw new SchemaChangeSetNameConflictException(entity.getPipelineId(), entity.getName());
            }
            throw ex;
        }
    }

    private List<SchemaChangeSetStatementEntity> insertStatements(SchemaChangeSetEntity changeSet,
                                                                  SchemaChangeStatementGate.GateResult validated) {
        var rows = new ArrayList<SchemaChangeSetStatementEntity>(validated.statements().size());
        var order = 0;
        for (var statement : validated.statements()) {
            var row = new SchemaChangeSetStatementEntity();
            row.setId(UUID.randomUUID());
            row.setChangeSet(changeSet);
            row.setSequenceOrder(order++);
            row.setSqlText(statement.sqlText());
            row.setQueryType(statement.queryType());
            rows.add(statementRepository.save(row));
        }
        return rows;
    }

    private static String checksumOf(SchemaChangeStatementGate.GateResult validated) {
        return SchemaChangeChecksum.of(validated.statements().stream()
                .map(SchemaChangeStatementGate.ClassifiedStatement::sqlText)
                .toList());
    }

    private SchemaChangeSetView toView(SchemaChangeSetEntity entity) {
        return toView(entity, statementRepository.findAllByChangeSet_IdOrderBySequenceOrderAsc(entity.getId()),
                List.of());
    }

    private static SchemaChangeSetView toView(SchemaChangeSetEntity e, List<SchemaChangeSetStatementEntity> rows,
                                              List<SchemaChangeStatementFinding> warnings) {
        return new SchemaChangeSetView(
                e.getId(), e.getOrganizationId(), e.getPipelineId(), e.getName(), e.getDescription(),
                e.getStatus(), e.getStatementsChecksum(), e.getCreatedBy(), e.getCreatedAt(), e.getUpdatedAt(),
                rows.stream().map(DefaultSchemaChangeSetService::toStatementView).toList(), warnings);
    }

    private static SchemaChangeSetStatementView toStatementView(SchemaChangeSetStatementEntity s) {
        return new SchemaChangeSetStatementView(s.getId(), s.getSequenceOrder(), s.getSqlText(), s.getQueryType(),
                s.getCreatedAt());
    }

    private static Pageable toPageable(PageRequest pageRequest) {
        return org.springframework.data.domain.PageRequest.of(pageRequest.page(), pageRequest.size());
    }

    private static <T> PageResponse<T> toPageResponse(Page<T> page) {
        return new PageResponse<>(page.getContent(), page.getNumber(),
                page.getSize() <= 0 ? 1 : page.getSize(), page.getTotalElements(), page.getTotalPages());
    }
}
