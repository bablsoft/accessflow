package com.bablsoft.accessflow.access.internal.scheduled;

import com.bablsoft.accessflow.access.internal.GrantUsageAggregationService;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GrantUsageAggregationJobTest {

    private static final Instant NOW = Instant.parse("2026-06-01T03:00:00Z");

    private final GrantUsageAggregationService service = mock(GrantUsageAggregationService.class);
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final GrantUsageAggregationJob job = new GrantUsageAggregationJob(service, clock);

    @Test
    void aggregatesEveryOrganizationWithTheInjectedClockInstant() {
        var orgA = UUID.randomUUID();
        var orgB = UUID.randomUUID();
        when(service.findOrganizationIds()).thenReturn(List.of(orgA, orgB));

        job.run();

        verify(service).aggregateOrganization(orgA, NOW);
        verify(service).aggregateOrganization(orgB, NOW);
    }

    @Test
    void doesNothingWhenThereAreNoOrganizations() {
        when(service.findOrganizationIds()).thenReturn(List.of());

        job.run();

        verify(service, never()).aggregateOrganization(any(), any());
    }

    /** One bad tenant must not cost every later tenant its refresh. */
    @Test
    void oneFailingOrganizationDoesNotAbortTheBatch() {
        var bad = UUID.randomUUID();
        var good = UUID.randomUUID();
        when(service.findOrganizationIds()).thenReturn(List.of(bad, good));
        doThrow(new IllegalStateException("boom"))
                .when(service).aggregateOrganization(eq(bad), any());

        job.run();

        verify(service).aggregateOrganization(good, NOW);
    }

    /**
     * Without the lock every replica folds the same audit window each tick and multiplies every
     * usage count — silent in single-replica dev, wrong in production.
     */
    @Test
    void isClusteredSafeAndConfigurable() throws Exception {
        var run = GrantUsageAggregationJob.class.getMethod("run");

        var lock = run.getAnnotation(SchedulerLock.class);
        assertThat(lock).isNotNull();
        assertThat(lock.name()).isEqualTo("grantUsageAggregationJob");
        assertThat(lock.lockAtMostFor()).isEqualTo("PT30M");
        assertThat(lock.lockAtLeastFor()).isEqualTo("PT2M");

        var scheduled = run.getAnnotation(Scheduled.class);
        assertThat(scheduled).isNotNull();
        assertThat(scheduled.fixedDelayString())
                .isEqualTo("${accessflow.access.usage.aggregation-poll-interval:PT1H}");
    }
}
