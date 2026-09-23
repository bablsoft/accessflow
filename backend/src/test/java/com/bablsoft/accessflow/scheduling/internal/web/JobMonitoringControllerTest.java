package com.bablsoft.accessflow.scheduling.internal.web;

import com.bablsoft.accessflow.core.api.PageRequest;
import com.bablsoft.accessflow.core.api.PageResponse;
import com.bablsoft.accessflow.scheduling.api.JobCadenceType;
import com.bablsoft.accessflow.scheduling.api.JobDescriptor;
import com.bablsoft.accessflow.scheduling.api.JobExecutionStatus;
import com.bablsoft.accessflow.scheduling.api.JobExecutionView;
import com.bablsoft.accessflow.scheduling.api.JobHealthSummary;
import com.bablsoft.accessflow.scheduling.api.JobMonitoringService;
import com.bablsoft.accessflow.scheduling.api.JobNotFoundException;
import com.bablsoft.accessflow.scheduling.api.JobRegistryView;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.support.StaticMessageSource;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JobMonitoringControllerTest {

    @Mock JobMonitoringService service;

    @Test
    void registryMapsEveryField() {
        var health = new JobHealthSummary(JobExecutionStatus.FAILED, false, Instant.EPOCH, Instant.EPOCH,
                12L, "boom", 2, 5, 2, 9L);
        when(service.registry()).thenReturn(new JobRegistryView(true, true, Duration.ofHours(24), List.of(
                new JobDescriptor("QueryTimeoutJob", "x.QueryTimeoutJob", "run", "workflow",
                        JobCadenceType.FIXED_DELAY, "PT5M", "queryTimeoutJob", Duration.ofMinutes(10), true, health),
                new JobDescriptor("GoneJob", null, null, null, null, null, null, null, false,
                        JobHealthSummary.empty()))));

        var response = new JobMonitoringController(service).registry();

        assertThat(response.schedulingEnabled()).isTrue();
        assertThat(response.summaryWindow()).isEqualTo("PT24H");
        var job = response.jobs().getFirst();
        assertThat(job.lockAtMostFor()).isEqualTo("PT10M");
        assertThat(job.health().consecutiveFailures()).isEqualTo(2);
        assertThat(job.health().lastErrorMessage()).isEqualTo("boom");
        assertThat(response.jobs().get(1).lockAtMostFor()).isNull();
        assertThat(response.jobs().get(1).registered()).isFalse();
    }

    @Test
    void executionsPassFiltersAndPage() {
        var from = Instant.parse("2026-09-01T00:00:00Z");
        var to = Instant.parse("2026-09-02T00:00:00Z");
        var view = new JobExecutionView(UUID.randomUUID(), "QueryTimeoutJob", "queryTimeoutJob", "pod-1",
                from, to, 5L, JobExecutionStatus.FAILED, "java.lang.IllegalStateException", "boom", false);
        when(service.executions(eq("QueryTimeoutJob"), eq(JobExecutionStatus.FAILED), eq(from), eq(to),
                eq(PageRequest.of(1, 10))))
                .thenReturn(new PageResponse<>(List.of(view), 1, 10, 11, 2));

        var response = new JobMonitoringController(service).executions("QueryTimeoutJob",
                JobExecutionStatus.FAILED, from, to, org.springframework.data.domain.PageRequest.of(1, 10));

        assertThat(response.totalElements()).isEqualTo(11);
        assertThat(response.content().getFirst().errorClass()).isEqualTo("java.lang.IllegalStateException");
        assertThat(response.content().getFirst().instanceId()).isEqualTo("pod-1");
    }

    @Test
    void unknownJobMapsToA404Problem() {
        var messages = new StaticMessageSource();
        messages.addMessage("error.job_not_found", Locale.getDefault(), "Scheduled job not found: {0}");
        messages.addMessage("error.job_not_found", Locale.ENGLISH, "Scheduled job not found: {0}");

        var problem = new JobMonitoringExceptionHandler(messages).handleJobNotFound(new JobNotFoundException("Nope"));

        assertThat(problem.getStatus()).isEqualTo(404);
        assertThat(problem.getDetail()).isEqualTo("Scheduled job not found: Nope");
        assertThat(problem.getProperties()).containsEntry("error", "JOB_NOT_FOUND").containsEntry("jobName", "Nope");
    }
}
