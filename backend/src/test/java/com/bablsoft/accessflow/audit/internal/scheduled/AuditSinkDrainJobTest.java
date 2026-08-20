package com.bablsoft.accessflow.audit.internal.scheduled;

import com.bablsoft.accessflow.audit.internal.sink.AuditSinkDrainService;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuditSinkDrainJobTest {

    private static final Instant NOW = Instant.parse("2026-08-19T12:00:00Z");

    private final AuditSinkDrainService drainService = mock(AuditSinkDrainService.class);
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final AuditSinkDrainJob job = new AuditSinkDrainJob(drainService, clock);

    @Test
    void drainsAllSinksWithTheInjectedClockInstant() {
        when(drainService.drainAll(NOW)).thenReturn(2);

        job.run();

        verify(drainService).drainAll(NOW);
    }

    @Test
    void runsQuietlyWhenNothingWasDelivered() {
        when(drainService.drainAll(NOW)).thenReturn(0);

        job.run();

        verify(drainService).drainAll(NOW);
    }

    /** Without the lock every replica would deliver every batch once per tick. */
    @Test
    void isClusteredSafeAndConfigurable() throws Exception {
        var run = AuditSinkDrainJob.class.getMethod("run");

        var lock = run.getAnnotation(SchedulerLock.class);
        assertThat(lock).isNotNull();
        assertThat(lock.name()).isEqualTo("auditSinkDrainJob");
        assertThat(lock.lockAtMostFor()).isEqualTo("PT10M");
        assertThat(lock.lockAtLeastFor()).isEqualTo("PT20S");

        var scheduled = run.getAnnotation(Scheduled.class);
        assertThat(scheduled).isNotNull();
        assertThat(scheduled.fixedDelayString())
                .isEqualTo("${accessflow.audit.sinks.drain-interval:PT30S}");
    }
}
