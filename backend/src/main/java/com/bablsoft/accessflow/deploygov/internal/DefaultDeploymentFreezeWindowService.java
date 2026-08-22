package com.bablsoft.accessflow.deploygov.internal;

import com.bablsoft.accessflow.core.api.PageRequest;
import com.bablsoft.accessflow.core.api.PageResponse;
import com.bablsoft.accessflow.deploygov.api.DeploymentEnvironmentNotFoundException;
import com.bablsoft.accessflow.deploygov.api.DeploymentFreezeWindowCommand;
import com.bablsoft.accessflow.deploygov.api.DeploymentFreezeWindowNotFoundException;
import com.bablsoft.accessflow.deploygov.api.DeploymentFreezeWindowService;
import com.bablsoft.accessflow.deploygov.api.DeploymentFreezeWindowView;
import com.bablsoft.accessflow.deploygov.api.DeploymentPipelineNotFoundException;
import com.bablsoft.accessflow.deploygov.api.IllegalDeploymentFreezeWindowException;
import com.bablsoft.accessflow.deploygov.internal.persistence.entity.DeploymentFreezeWindowEntity;
import com.bablsoft.accessflow.deploygov.internal.persistence.repo.DeploymentEnvironmentRepository;
import com.bablsoft.accessflow.deploygov.internal.persistence.repo.DeploymentFreezeWindowRepository;
import com.bablsoft.accessflow.deploygov.internal.persistence.repo.DeploymentPipelineRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class DefaultDeploymentFreezeWindowService implements DeploymentFreezeWindowService {

    private final DeploymentFreezeWindowRepository freezeWindowRepository;
    private final DeploymentPipelineRepository pipelineRepository;
    private final DeploymentEnvironmentRepository environmentRepository;
    private final MessageSource messageSource;

    @Override
    @Transactional(readOnly = true)
    public PageResponse<DeploymentFreezeWindowView> list(UUID organizationId, PageRequest pageRequest) {
        var page = freezeWindowRepository.findByOrganizationId(organizationId, toPageable(pageRequest));
        return toPageResponse(page.map(DefaultDeploymentFreezeWindowService::toView));
    }

    @Override
    @Transactional(readOnly = true)
    public DeploymentFreezeWindowView get(UUID id, UUID organizationId) {
        return toView(require(id, organizationId));
    }

    @Override
    @Transactional
    public DeploymentFreezeWindowView create(DeploymentFreezeWindowCommand command) {
        validate(command);
        var entity = new DeploymentFreezeWindowEntity();
        entity.setId(UUID.randomUUID());
        entity.setOrganizationId(command.organizationId());
        apply(entity, command);
        return toView(freezeWindowRepository.save(entity));
    }

    @Override
    @Transactional
    public DeploymentFreezeWindowView update(UUID id, DeploymentFreezeWindowCommand command) {
        var entity = require(id, command.organizationId());
        validate(command);
        apply(entity, command);
        return toView(freezeWindowRepository.save(entity));
    }

    @Override
    @Transactional
    public void delete(UUID id, UUID organizationId) {
        freezeWindowRepository.delete(require(id, organizationId));
    }

    private DeploymentFreezeWindowEntity require(UUID id, UUID organizationId) {
        return freezeWindowRepository.findByIdAndOrganizationId(id, organizationId)
                .orElseThrow(() -> new DeploymentFreezeWindowNotFoundException(id));
    }

    /** Full replacement of every definition field — the command always carries the whole shape. */
    private static void apply(DeploymentFreezeWindowEntity entity, DeploymentFreezeWindowCommand command) {
        entity.setPipelineId(command.pipelineId());
        entity.setEnvironmentId(command.environmentId());
        entity.setStartsAt(command.startsAt());
        entity.setEndsAt(command.endsAt());
        entity.setDaysOfWeek(toDays(command.daysOfWeek()));
        entity.setStartTime(command.startTime());
        entity.setEndTime(command.endTime());
        entity.setTimezone(command.timezone());
        entity.setBehavior(command.behavior());
        entity.setReason(command.reason());
        entity.setEnabled(command.enabled() == null || command.enabled());
    }

    private void validate(DeploymentFreezeWindowCommand command) {
        validateScope(command);
        var oneOff = command.startsAt() != null || command.endsAt() != null;
        var recurring = (command.daysOfWeek() != null && !command.daysOfWeek().isEmpty())
                || command.startTime() != null || command.endTime() != null
                || command.timezone() != null;
        if (oneOff == recurring) {
            throw invalid("error.deployment_freeze_window_invalid_shape");
        }
        if (oneOff) {
            validateOneOff(command);
        } else {
            validateRecurring(command);
        }
    }

    private void validateScope(DeploymentFreezeWindowCommand command) {
        if (command.pipelineId() != null) {
            pipelineRepository.findByIdAndOrganizationId(command.pipelineId(), command.organizationId())
                    .orElseThrow(() -> new DeploymentPipelineNotFoundException(command.pipelineId()));
        }
        if (command.environmentId() == null) {
            return;
        }
        if (command.pipelineId() == null) {
            throw invalid("error.deployment_freeze_window_invalid_scope");
        }
        environmentRepository.findById(command.environmentId())
                .filter(e -> e.getPipelineId().equals(command.pipelineId()))
                .orElseThrow(() -> new DeploymentEnvironmentNotFoundException(command.environmentId()));
    }

    private void validateOneOff(DeploymentFreezeWindowCommand command) {
        if (command.startsAt() == null || command.endsAt() == null) {
            throw invalid("error.deployment_freeze_window_invalid_shape");
        }
        if (!command.endsAt().isAfter(command.startsAt())) {
            throw invalid("error.deployment_freeze_window_invalid_bounds");
        }
    }

    private void validateRecurring(DeploymentFreezeWindowCommand command) {
        if (command.daysOfWeek() == null || command.daysOfWeek().isEmpty()
                || command.startTime() == null || command.endTime() == null
                || command.timezone() == null || command.timezone().isBlank()) {
            throw invalid("error.deployment_freeze_window_invalid_shape");
        }
        for (var day : command.daysOfWeek()) {
            if (day == null || day < 1 || day > 7) {
                throw invalid("error.deployment_freeze_window_invalid_days", day);
            }
        }
        if (command.startTime().equals(command.endTime())) {
            throw invalid("error.deployment_freeze_window_invalid_times");
        }
        try {
            ZoneId.of(command.timezone());
        } catch (DateTimeException ex) {
            throw invalid("error.deployment_freeze_window_invalid_timezone", command.timezone());
        }
    }

    private IllegalDeploymentFreezeWindowException invalid(String key, Object... args) {
        return new IllegalDeploymentFreezeWindowException(
                messageSource.getMessage(key, args.length == 0 ? null : args,
                        LocaleContextHolder.getLocale()));
    }

    private static short[] toDays(List<Integer> days) {
        if (days == null || days.isEmpty()) {
            return null;
        }
        var distinct = days.stream().distinct().sorted().toList();
        var result = new short[distinct.size()];
        for (int i = 0; i < distinct.size(); i++) {
            result[i] = distinct.get(i).shortValue();
        }
        return result;
    }

    private static DeploymentFreezeWindowView toView(DeploymentFreezeWindowEntity e) {
        List<Integer> days = null;
        if (e.getDaysOfWeek() != null) {
            var boxed = new Integer[e.getDaysOfWeek().length];
            for (int i = 0; i < e.getDaysOfWeek().length; i++) {
                boxed[i] = (int) e.getDaysOfWeek()[i];
            }
            days = List.of(boxed);
        }
        return new DeploymentFreezeWindowView(
                e.getId(), e.getOrganizationId(), e.getPipelineId(), e.getEnvironmentId(),
                e.getStartsAt(), e.getEndsAt(), days, e.getStartTime(), e.getEndTime(),
                e.getTimezone(), e.getBehavior(), e.getReason(), e.isEnabled(), e.getCreatedAt());
    }

    private static Pageable toPageable(PageRequest pageRequest) {
        return org.springframework.data.domain.PageRequest.of(pageRequest.page(), pageRequest.size());
    }

    private static <T> PageResponse<T> toPageResponse(Page<T> page) {
        return new PageResponse<>(page.getContent(), page.getNumber(),
                page.getSize() <= 0 ? 1 : page.getSize(), page.getTotalElements(), page.getTotalPages());
    }
}
