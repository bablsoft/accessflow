package com.bablsoft.accessflow.schemachange.internal.scheduled;

import com.bablsoft.accessflow.schemachange.internal.SchemaDriftScanCoordinator;
import com.bablsoft.accessflow.schemachange.internal.persistence.entity.SchemaDriftConfigEntity;
import com.bablsoft.accessflow.schemachange.internal.persistence.repo.SchemaDriftConfigRepository;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SchemaDriftJobTest {

    private static final Instant NOW = Instant.parse("2026-09-22T10:00:00Z");

    @Mock SchemaDriftConfigRepository configRepository;
    @Mock SchemaDriftScanCoordinator scanCoordinator;

    private SchemaDriftJob job() {
        return new SchemaDriftJob(configRepository, scanCoordinator, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static SchemaDriftConfigEntity config(Instant lastScanAt, int intervalHours) {
        var config = new SchemaDriftConfigEntity();
        config.setId(UUID.randomUUID());
        config.setOrganizationId(UUID.randomUUID());
        config.setPipelineId(UUID.randomUUID());
        config.setEnabled(true);
        config.setScanIntervalHours(intervalHours);
        config.setLastScanAt(lastScanAt);
        return config;
    }

    @Test
    void scansNeverScannedAndOverduePipelinesOnly() {
        var neverScanned = config(null, 24);
        var overdue = config(NOW.minusSeconds(25 * 3600), 24);
        var fresh = config(NOW.minusSeconds(3600), 24);
        when(configRepository.findAllByEnabledTrue()).thenReturn(List.of(neverScanned, overdue, fresh));

        job().run();

        verify(scanCoordinator).scanPipeline(neverScanned);
        verify(scanCoordinator).scanPipeline(overdue);
        verify(scanCoordinator, never()).scanPipeline(fresh);
    }

    @Test
    void aPipelineDueExactlyOnTheBoundaryIsScanned() {
        var boundary = config(NOW.minusSeconds(24 * 3600), 24);
        when(configRepository.findAllByEnabledTrue()).thenReturn(List.of(boundary));

        job().run();

        verify(scanCoordinator).scanPipeline(boundary);
    }

    @Test
    void perPipelineFailureDoesNotAbortTheBatch() {
        var failing = config(null, 24);
        var healthy = config(null, 24);
        when(configRepository.findAllByEnabledTrue()).thenReturn(List.of(failing, healthy));
        doThrow(new IllegalStateException("boom")).when(scanCoordinator).scanPipeline(failing);

        job().run();

        verify(scanCoordinator).scanPipeline(healthy);
    }

    @Test
    void noDuePipelinesDoesNothing() {
        when(configRepository.findAllByEnabledTrue()).thenReturn(List.of());

        job().run();

        verifyNoInteractions(scanCoordinator);
    }

    @Test
    void disabledPipelinesAreNeverEvenLoaded() {
        when(configRepository.findAllByEnabledTrue()).thenReturn(List.of());

        job().run();

        // Drift is opt-in: the drain query itself is the filter, so an upgrade never starts scanning.
        verify(configRepository).findAllByEnabledTrue();
        verify(configRepository, never()).findAll();
    }

    /**
     * A missing {@code @SchedulerLock} runs the job once per replica per tick, and this one opens
     * connections to customer databases. Asserted by reflection rather than an ArchUnit presence
     * rule because the values matter as much as the annotation: a lock with the wrong name shares a
     * namespace with somebody else's job, and one that expires mid-run admits the second scanner it
     * exists to keep out.
     */
    @Test
    void theJobMethodIsSchedulerLockedWithTheRightNameAndHorizons() throws NoSuchMethodException {
        var run = SchemaDriftJob.class.getMethod("run");

        var lock = run.getAnnotation(SchedulerLock.class);
        assertThat(lock).isNotNull();
        assertThat(lock.name()).isEqualTo("schemaDriftJob");
        assertThat(lock.lockAtMostFor()).isEqualTo("PT2H");
        assertThat(lock.lockAtLeastFor()).isEqualTo("PT5M");

        var scheduled = run.getAnnotation(Scheduled.class);
        assertThat(scheduled).isNotNull();
        assertThat(scheduled.fixedDelayString())
                .isEqualTo("${accessflow.schemachange.drift-poll-interval:PT6H}");
    }

    @Test
    void duenessIsMeasuredAgainstTheInjectedClockNotWallTime() {
        // The same config is not due at NOW and due at NOW + 30h. Only an injected clock can move
        // that boundary, so this fails the moment somebody reaches for Instant.now().
        var config = config(NOW.minusSeconds(3600), 24);
        when(configRepository.findAllByEnabledTrue()).thenReturn(List.of(config));

        new SchemaDriftJob(configRepository, scanCoordinator, Clock.fixed(NOW, ZoneOffset.UTC)).run();
        verify(scanCoordinator, never()).scanPipeline(config);

        new SchemaDriftJob(configRepository, scanCoordinator,
                Clock.fixed(NOW.plusSeconds(30 * 3600), ZoneOffset.UTC)).run();
        verify(scanCoordinator).scanPipeline(config);
    }
}
