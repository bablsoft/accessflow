package com.bablsoft.accessflow.deploygov.internal;

import com.bablsoft.accessflow.core.api.PageRequest;
import com.bablsoft.accessflow.deploygov.api.DeploymentEnvironmentNotFoundException;
import com.bablsoft.accessflow.deploygov.api.DeploymentFreezeWindowCommand;
import com.bablsoft.accessflow.deploygov.api.DeploymentFreezeWindowNotFoundException;
import com.bablsoft.accessflow.deploygov.api.DeploymentPipelineNotFoundException;
import com.bablsoft.accessflow.deploygov.api.FreezeBehavior;
import com.bablsoft.accessflow.deploygov.api.IllegalDeploymentFreezeWindowException;
import com.bablsoft.accessflow.deploygov.internal.persistence.entity.DeploymentEnvironmentEntity;
import com.bablsoft.accessflow.deploygov.internal.persistence.entity.DeploymentFreezeWindowEntity;
import com.bablsoft.accessflow.deploygov.internal.persistence.entity.DeploymentPipelineEntity;
import com.bablsoft.accessflow.deploygov.internal.persistence.repo.DeploymentEnvironmentRepository;
import com.bablsoft.accessflow.deploygov.internal.persistence.repo.DeploymentFreezeWindowRepository;
import com.bablsoft.accessflow.deploygov.internal.persistence.repo.DeploymentPipelineRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.MessageSource;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultDeploymentFreezeWindowServiceTest {

    @Mock
    private DeploymentFreezeWindowRepository freezeWindowRepository;

    @Mock
    private DeploymentPipelineRepository pipelineRepository;

    @Mock
    private DeploymentEnvironmentRepository environmentRepository;

    @Mock
    private MessageSource messageSource;

    private DefaultDeploymentFreezeWindowService service;

    private final UUID orgId = UUID.randomUUID();
    private final UUID pipelineId = UUID.randomUUID();
    private final UUID environmentId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        lenient().when(messageSource.getMessage(any(String.class), any(), any()))
                .thenAnswer(inv -> inv.getArgument(0));
        service = new DefaultDeploymentFreezeWindowService(freezeWindowRepository,
                pipelineRepository, environmentRepository, messageSource);
    }

    @Test
    void listMapsPage() {
        when(freezeWindowRepository.findByOrganizationId(any(UUID.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(persistedOneOff())));

        var page = service.list(orgId, PageRequest.of(0, 20));

        assertThat(page.content()).hasSize(1);
        assertThat(page.content().get(0).behavior()).isEqualTo(FreezeBehavior.HOLD);
    }

    @Test
    void getThrowsWhenMissingOrCrossOrg() {
        var id = UUID.randomUUID();
        when(freezeWindowRepository.findByIdAndOrganizationId(id, orgId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(id, orgId))
                .isInstanceOf(DeploymentFreezeWindowNotFoundException.class);
    }

    @Test
    void createPersistsOneOffWindow() {
        when(freezeWindowRepository.save(any(DeploymentFreezeWindowEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        var startsAt = Instant.parse("2026-12-24T00:00:00Z");
        var endsAt = Instant.parse("2027-01-02T00:00:00Z");

        var view = service.create(oneOffCommand(startsAt, endsAt));

        assertThat(view.startsAt()).isEqualTo(startsAt);
        assertThat(view.endsAt()).isEqualTo(endsAt);
        assertThat(view.daysOfWeek()).isNull();
        assertThat(view.enabled()).isTrue();
    }

    @Test
    void createPersistsRecurringWindowWithSortedDistinctDays() {
        when(freezeWindowRepository.save(any(DeploymentFreezeWindowEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        var view = service.create(recurringCommand(List.of(7, 5, 5, 6)));

        assertThat(view.daysOfWeek()).containsExactly(5, 6, 7);
        assertThat(view.timezone()).isEqualTo("Europe/Berlin");
    }

    @Test
    void createRejectsMixedShape() {
        var command = new DeploymentFreezeWindowCommand(orgId, null, null,
                Instant.now(), Instant.now().plusSeconds(60), List.of(5), LocalTime.of(18, 0),
                LocalTime.of(22, 0), "Europe/Berlin", FreezeBehavior.HOLD, null, null);

        assertThatThrownBy(() -> service.create(command))
                .isInstanceOf(IllegalDeploymentFreezeWindowException.class)
                .hasMessage("error.deployment_freeze_window_invalid_shape");
        verify(freezeWindowRepository, never()).save(any());
    }

    @Test
    void createRejectsEmptyShape() {
        var command = new DeploymentFreezeWindowCommand(orgId, null, null, null, null, null, null,
                null, null, FreezeBehavior.HOLD, null, null);

        assertThatThrownBy(() -> service.create(command))
                .isInstanceOf(IllegalDeploymentFreezeWindowException.class)
                .hasMessage("error.deployment_freeze_window_invalid_shape");
    }

    @Test
    void createRejectsIncompleteOneOff() {
        var command = new DeploymentFreezeWindowCommand(orgId, null, null, Instant.now(), null,
                null, null, null, null, FreezeBehavior.HOLD, null, null);

        assertThatThrownBy(() -> service.create(command))
                .isInstanceOf(IllegalDeploymentFreezeWindowException.class)
                .hasMessage("error.deployment_freeze_window_invalid_shape");
    }

    @Test
    void createRejectsInvertedOneOffBounds() {
        var command = oneOffCommand(Instant.now(), Instant.now().minusSeconds(60));

        assertThatThrownBy(() -> service.create(command))
                .isInstanceOf(IllegalDeploymentFreezeWindowException.class)
                .hasMessage("error.deployment_freeze_window_invalid_bounds");
    }

    @Test
    void createRejectsOutOfRangeDay() {
        assertThatThrownBy(() -> service.create(recurringCommand(List.of(0))))
                .isInstanceOf(IllegalDeploymentFreezeWindowException.class)
                .hasMessage("error.deployment_freeze_window_invalid_days");
        assertThatThrownBy(() -> service.create(recurringCommand(List.of(8))))
                .isInstanceOf(IllegalDeploymentFreezeWindowException.class)
                .hasMessage("error.deployment_freeze_window_invalid_days");
    }

    @Test
    void createRejectsEqualStartAndEndTimes() {
        var command = new DeploymentFreezeWindowCommand(orgId, null, null, null, null, List.of(5),
                LocalTime.of(18, 0), LocalTime.of(18, 0), "Europe/Berlin", FreezeBehavior.HOLD,
                null, null);

        assertThatThrownBy(() -> service.create(command))
                .isInstanceOf(IllegalDeploymentFreezeWindowException.class)
                .hasMessage("error.deployment_freeze_window_invalid_times");
    }

    @Test
    void createRejectsUnknownTimezone() {
        var command = new DeploymentFreezeWindowCommand(orgId, null, null, null, null, List.of(5),
                LocalTime.of(18, 0), LocalTime.of(22, 0), "Not/AZone", FreezeBehavior.HOLD,
                null, null);

        assertThatThrownBy(() -> service.create(command))
                .isInstanceOf(IllegalDeploymentFreezeWindowException.class)
                .hasMessage("error.deployment_freeze_window_invalid_timezone");
    }

    @Test
    void createRejectsUnknownPipelineScope() {
        when(pipelineRepository.findByIdAndOrganizationId(pipelineId, orgId))
                .thenReturn(Optional.empty());
        var command = new DeploymentFreezeWindowCommand(orgId, pipelineId, null,
                Instant.now(), Instant.now().plusSeconds(60), null, null, null, null,
                FreezeBehavior.HOLD, null, null);

        assertThatThrownBy(() -> service.create(command))
                .isInstanceOf(DeploymentPipelineNotFoundException.class);
    }

    @Test
    void createRejectsEnvironmentScopeWithoutPipeline() {
        var command = new DeploymentFreezeWindowCommand(orgId, null, environmentId,
                Instant.now(), Instant.now().plusSeconds(60), null, null, null, null,
                FreezeBehavior.HOLD, null, null);

        assertThatThrownBy(() -> service.create(command))
                .isInstanceOf(IllegalDeploymentFreezeWindowException.class)
                .hasMessage("error.deployment_freeze_window_invalid_scope");
    }

    @Test
    void createRejectsEnvironmentOfAnotherPipeline() {
        stubPipeline();
        var foreignEnvironment = new DeploymentEnvironmentEntity();
        foreignEnvironment.setId(environmentId);
        foreignEnvironment.setPipelineId(UUID.randomUUID());
        when(environmentRepository.findById(environmentId))
                .thenReturn(Optional.of(foreignEnvironment));
        var command = new DeploymentFreezeWindowCommand(orgId, pipelineId, environmentId,
                Instant.now(), Instant.now().plusSeconds(60), null, null, null, null,
                FreezeBehavior.HOLD, null, null);

        assertThatThrownBy(() -> service.create(command))
                .isInstanceOf(DeploymentEnvironmentNotFoundException.class);
    }

    @Test
    void createAcceptsEnvironmentScopedWindow() {
        stubPipeline();
        var environment = new DeploymentEnvironmentEntity();
        environment.setId(environmentId);
        environment.setPipelineId(pipelineId);
        when(environmentRepository.findById(environmentId)).thenReturn(Optional.of(environment));
        when(freezeWindowRepository.save(any(DeploymentFreezeWindowEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        var command = new DeploymentFreezeWindowCommand(orgId, pipelineId, environmentId,
                Instant.now(), Instant.now().plusSeconds(60), null, null, null, null,
                FreezeBehavior.REJECT, "release freeze", false);

        var view = service.create(command);

        assertThat(view.pipelineId()).isEqualTo(pipelineId);
        assertThat(view.environmentId()).isEqualTo(environmentId);
        assertThat(view.behavior()).isEqualTo(FreezeBehavior.REJECT);
        assertThat(view.enabled()).isFalse();
    }

    @Test
    void updateReplacesTheWholeDefinition() {
        var existing = persistedOneOff();
        when(freezeWindowRepository.findByIdAndOrganizationId(existing.getId(), orgId))
                .thenReturn(Optional.of(existing));
        when(freezeWindowRepository.save(any(DeploymentFreezeWindowEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        var view = service.update(existing.getId(), recurringCommand(List.of(6, 7)));

        assertThat(view.startsAt()).isNull();
        assertThat(view.endsAt()).isNull();
        assertThat(view.daysOfWeek()).containsExactly(6, 7);
        assertThat(view.startTime()).isEqualTo(LocalTime.of(18, 0));
    }

    @Test
    void updateRejectsMissingWindow() {
        var id = UUID.randomUUID();
        when(freezeWindowRepository.findByIdAndOrganizationId(id, orgId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.update(id, recurringCommand(List.of(5))))
                .isInstanceOf(DeploymentFreezeWindowNotFoundException.class);
    }

    @Test
    void deleteRemovesOwnedWindow() {
        var existing = persistedOneOff();
        when(freezeWindowRepository.findByIdAndOrganizationId(existing.getId(), orgId))
                .thenReturn(Optional.of(existing));

        service.delete(existing.getId(), orgId);

        verify(freezeWindowRepository).delete(existing);
    }

    private void stubPipeline() {
        var pipeline = new DeploymentPipelineEntity();
        pipeline.setId(pipelineId);
        pipeline.setOrganizationId(orgId);
        when(pipelineRepository.findByIdAndOrganizationId(pipelineId, orgId))
                .thenReturn(Optional.of(pipeline));
    }

    private DeploymentFreezeWindowCommand oneOffCommand(Instant startsAt, Instant endsAt) {
        return new DeploymentFreezeWindowCommand(orgId, null, null, startsAt, endsAt, null, null,
                null, null, FreezeBehavior.HOLD, "maintenance", null);
    }

    private DeploymentFreezeWindowCommand recurringCommand(List<Integer> days) {
        return new DeploymentFreezeWindowCommand(orgId, null, null, null, null, days,
                LocalTime.of(18, 0), LocalTime.of(22, 0), "Europe/Berlin", FreezeBehavior.HOLD,
                null, null);
    }

    private DeploymentFreezeWindowEntity persistedOneOff() {
        var e = new DeploymentFreezeWindowEntity();
        e.setId(UUID.randomUUID());
        e.setOrganizationId(orgId);
        e.setStartsAt(Instant.parse("2026-12-24T00:00:00Z"));
        e.setEndsAt(Instant.parse("2027-01-02T00:00:00Z"));
        e.setBehavior(FreezeBehavior.HOLD);
        return e;
    }
}
