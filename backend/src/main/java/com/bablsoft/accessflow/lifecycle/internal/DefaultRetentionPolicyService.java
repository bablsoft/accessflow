package com.bablsoft.accessflow.lifecycle.internal;

import com.bablsoft.accessflow.core.api.PageRequest;
import com.bablsoft.accessflow.core.api.PageResponse;
import com.bablsoft.accessflow.lifecycle.api.CreateRetentionPolicyCommand;
import com.bablsoft.accessflow.lifecycle.api.InvalidRetentionPolicyException;
import com.bablsoft.accessflow.lifecycle.api.LifecycleAction;
import com.bablsoft.accessflow.lifecycle.api.LifecyclePreviewResult;
import com.bablsoft.accessflow.lifecycle.api.LifecycleTransform;
import com.bablsoft.accessflow.lifecycle.api.RetentionPolicyNotFoundException;
import com.bablsoft.accessflow.lifecycle.api.RetentionPolicyService;
import com.bablsoft.accessflow.lifecycle.api.RetentionPolicyView;
import com.bablsoft.accessflow.lifecycle.api.UpdateRetentionPolicyCommand;
import com.bablsoft.accessflow.lifecycle.internal.persistence.entity.RetentionPolicyEntity;
import com.bablsoft.accessflow.lifecycle.internal.persistence.repo.RetentionPolicyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
class DefaultRetentionPolicyService implements RetentionPolicyService {

    private final RetentionPolicyRepository repository;
    private final RetentionPolicyViewMapper mapper;
    private final LifecyclePreviewCalculator previewCalculator;
    private final Clock clock;

    @Override
    @Transactional
    public RetentionPolicyView create(CreateRetentionPolicyCommand command) {
        validate(command.targetTable(), command.classificationTag(), command.action(),
                command.transformType(), command.retentionWindow());
        var entity = new RetentionPolicyEntity();
        entity.setId(UUID.randomUUID());
        entity.setOrganizationId(command.organizationId());
        entity.setDatasourceId(command.datasourceId());
        entity.setCreatedBy(command.createdBy());
        apply(entity, command.name(), command.description(), command.targetTable(),
                command.targetColumns(), command.classificationTag(), command.timestampColumn(),
                command.retentionWindow(), command.action(), command.transformType(),
                command.softDeleteColumn(), command.enabled());
        Instant now = clock.instant();
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        return mapper.toView(repository.save(entity));
    }

    @Override
    @Transactional
    public RetentionPolicyView update(UpdateRetentionPolicyCommand command) {
        validate(command.targetTable(), command.classificationTag(), command.action(),
                command.transformType(), command.retentionWindow());
        var entity = repository.findByIdAndOrganizationId(command.policyId(), command.organizationId())
                .orElseThrow(() -> new RetentionPolicyNotFoundException(command.policyId()));
        apply(entity, command.name(), command.description(), command.targetTable(),
                command.targetColumns(), command.classificationTag(), command.timestampColumn(),
                command.retentionWindow(), command.action(), command.transformType(),
                command.softDeleteColumn(), command.enabled());
        return mapper.toView(repository.save(entity));
    }

    @Override
    @Transactional(readOnly = true)
    public RetentionPolicyView get(UUID policyId, UUID organizationId) {
        return mapper.toView(load(policyId, organizationId));
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<RetentionPolicyView> list(UUID organizationId, PageRequest pageRequest) {
        var page = repository.findAllByOrganizationId(organizationId,
                LifecyclePageAdapter.toSpringPageable(pageRequest));
        return LifecyclePageAdapter.toPageResponse(page).map(mapper::toView);
    }

    @Override
    @Transactional
    public void delete(UUID policyId, UUID organizationId) {
        repository.delete(load(policyId, organizationId));
    }

    @Override
    public LifecyclePreviewResult preview(UUID policyId, UUID organizationId) {
        // No surrounding @Transactional: the calculator delegates to the proxy's dry-run, whose
        // own datasource lookup may throw (e.g. datasource not pooled). The calculator swallows that
        // into a best-effort -1 estimate, but a surrounding tx would already be marked rollback-only.
        return previewCalculator.preview(load(policyId, organizationId));
    }

    private RetentionPolicyEntity load(UUID policyId, UUID organizationId) {
        return repository.findByIdAndOrganizationId(policyId, organizationId)
                .orElseThrow(() -> new RetentionPolicyNotFoundException(policyId));
    }

    private void apply(RetentionPolicyEntity entity, String name, String description,
                       String targetTable, List<String> targetColumns, String classificationTag,
                       String timestampColumn, String retentionWindow, LifecycleAction action,
                       LifecycleTransform transformType, String softDeleteColumn, boolean enabled) {
        entity.setName(name);
        entity.setDescription(description);
        entity.setTargetTable(blankToNull(targetTable));
        entity.setTargetColumns(targetColumns == null ? new String[0]
                : targetColumns.toArray(String[]::new));
        entity.setClassificationTag(blankToNull(classificationTag));
        entity.setTimestampColumn(timestampColumn);
        entity.setRetentionWindow(retentionWindow);
        entity.setAction(action);
        entity.setTransformType(transformType);
        entity.setSoftDeleteColumn(blankToNull(softDeleteColumn));
        entity.setEnabled(enabled);
    }

    private void validate(String targetTable, String classificationTag, LifecycleAction action,
                          LifecycleTransform transformType, String retentionWindow) {
        if (isBlank(targetTable) && isBlank(classificationTag)) {
            throw new InvalidRetentionPolicyException(InvalidRetentionPolicyException.Reason.NO_TARGET);
        }
        if (action == LifecycleAction.PSEUDONYMIZE && transformType == null) {
            throw new InvalidRetentionPolicyException(
                    InvalidRetentionPolicyException.Reason.TRANSFORM_REQUIRED);
        }
        if (action != LifecycleAction.PSEUDONYMIZE && transformType != null) {
            throw new InvalidRetentionPolicyException(
                    InvalidRetentionPolicyException.Reason.TRANSFORM_NOT_ALLOWED);
        }
        try {
            RetentionWindow.parse(retentionWindow);
        } catch (IllegalArgumentException ex) {
            throw new InvalidRetentionPolicyException(
                    InvalidRetentionPolicyException.Reason.INVALID_WINDOW);
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String blankToNull(String s) {
        return isBlank(s) ? null : s;
    }
}
