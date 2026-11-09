package com.bablsoft.accessflow.apigov.internal.scheduled;

import com.bablsoft.accessflow.apigov.api.ApiConnectorLimitKind;
import com.bablsoft.accessflow.apigov.api.ApiConnectorQuotaExceededException;
import com.bablsoft.accessflow.apigov.internal.ApiExecutionService;
import com.bablsoft.accessflow.apigov.internal.ApiExecutionService.ExecutionTrigger;
import com.bablsoft.accessflow.apigov.internal.persistence.repo.ApiRequestRepository;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ApiRequestRunJobTest {

    private static final Instant NOW = Instant.parse("2026-11-09T10:00:00Z");

    @Mock
    private ApiRequestRepository requestRepository;
    @Mock
    private ApiExecutionService executionService;

    private ApiRequestRunJob job;
    private final Logger logger = (Logger) LoggerFactory.getLogger(ApiRequestRunJob.class);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private Level previousLevel;

    @BeforeEach
    void setUp() {
        job = new ApiRequestRunJob(requestRepository, executionService, Clock.fixed(NOW, ZoneOffset.UTC));
        previousLevel = logger.getLevel();
        logger.setLevel(Level.DEBUG);
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
        logger.setLevel(previousLevel);
    }

    @Test
    void runsScheduledAndThrottledIdsWithTheirTriggers() {
        var scheduled = UUID.randomUUID();
        var throttled = UUID.randomUUID();
        when(requestRepository.findScheduledDueIds(NOW)).thenReturn(List.of(scheduled));
        when(requestRepository.findThrottledDueIds(NOW)).thenReturn(List.of(throttled));

        job.run();

        verify(executionService).execute(scheduled, ExecutionTrigger.SCHEDULED);
        verify(executionService).execute(throttled, ExecutionTrigger.QUOTA_RETRY);
    }

    @Test
    void reThrottleLogsDebugNotError() {
        var id = UUID.randomUUID();
        when(requestRepository.findScheduledDueIds(NOW)).thenReturn(List.of());
        when(requestRepository.findThrottledDueIds(NOW)).thenReturn(List.of(id));
        when(executionService.execute(id, ExecutionTrigger.QUOTA_RETRY))
                .thenThrow(new ApiConnectorQuotaExceededException(UUID.randomUUID(),
                        ApiConnectorLimitKind.MINUTE, 10, NOW.plusSeconds(10)));

        job.run();

        assertThat(appender.list).extracting(ILoggingEvent::getLevel)
                .contains(Level.DEBUG).doesNotContain(Level.ERROR);
    }

    @Test
    void oneFailingRowDoesNotAbortTheBatch() {
        var failing = UUID.randomUUID();
        var next = UUID.randomUUID();
        var throttled = UUID.randomUUID();
        when(requestRepository.findScheduledDueIds(NOW)).thenReturn(List.of(failing, next));
        when(requestRepository.findThrottledDueIds(NOW)).thenReturn(List.of(throttled));
        when(executionService.execute(failing, ExecutionTrigger.SCHEDULED))
                .thenThrow(new IllegalStateException("boom"));

        job.run();

        verify(executionService).execute(next, ExecutionTrigger.SCHEDULED);
        verify(executionService).execute(throttled, ExecutionTrigger.QUOTA_RETRY);
        assertThat(appender.list).extracting(ILoggingEvent::getLevel).contains(Level.ERROR);
    }
}
