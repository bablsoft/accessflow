package com.bablsoft.accessflow.apigov.internal.scheduled;

import com.bablsoft.accessflow.apigov.api.ApiSchemaFetchException;
import com.bablsoft.accessflow.apigov.api.ApiSchemaParseException;
import com.bablsoft.accessflow.apigov.api.ApiSchemaRefreshResult;
import com.bablsoft.accessflow.apigov.api.ApiSchemaRevisionService;
import com.bablsoft.accessflow.apigov.internal.persistence.repo.ApiSchemaRefreshCandidate;
import com.bablsoft.accessflow.apigov.internal.persistence.repo.ApiSchemaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ApiSchemaRefreshJobTest {

    private static final Instant NOW = Instant.parse("2026-11-06T10:00:00Z");

    @Mock private ApiSchemaRepository schemaRepository;
    @Mock private ApiSchemaRevisionService revisionService;

    private ApiSchemaRefreshJob job;

    @BeforeEach
    void setUp() {
        job = new ApiSchemaRefreshJob(schemaRepository, revisionService, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static ApiSchemaRefreshCandidate candidate(int intervalMinutes, Instant lastRefreshAt) {
        return new ApiSchemaRefreshCandidate(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                intervalMinutes, lastRefreshAt);
    }

    private void refreshOf(ApiSchemaRefreshCandidate c, ApiSchemaRefreshResult result) {
        when(revisionService.refresh(c.connectorId(), c.organizationId(), c.schemaId())).thenReturn(result);
    }

    @Test
    void refreshesOnlyDueSchemas() {
        var neverRefreshed = candidate(60, null);
        var due = candidate(60, NOW.minus(Duration.ofMinutes(60)));
        var notDue = candidate(60, NOW.minus(Duration.ofMinutes(59)));
        when(schemaRepository.findRefreshCandidates()).thenReturn(List.of(neverRefreshed, due, notDue));
        refreshOf(neverRefreshed, new ApiSchemaRefreshResult(UUID.randomUUID(), 2, false));
        refreshOf(due, ApiSchemaRefreshResult.unchangedResult());

        job.run();

        verify(revisionService).refresh(neverRefreshed.connectorId(), neverRefreshed.organizationId(),
                neverRefreshed.schemaId());
        verify(revisionService).refresh(due.connectorId(), due.organizationId(), due.schemaId());
        verify(revisionService, never()).refresh(notDue.connectorId(), notDue.organizationId(), notDue.schemaId());
    }

    @Test
    void doesNothingWhenNoSchemaOptedIn() {
        when(schemaRepository.findRefreshCandidates()).thenReturn(List.of());

        job.run();

        verifyNoInteractions(revisionService);
    }

    @Test
    void aFailingSchemaDoesNotStopTheRest() {
        var fetchFails = candidate(5, null);
        var parseFails = candidate(5, null);
        var crashes = candidate(5, null);
        var healthy = candidate(5, null);
        when(schemaRepository.findRefreshCandidates()).thenReturn(List.of(fetchFails, parseFails, crashes, healthy));
        when(revisionService.refresh(fetchFails.connectorId(), fetchFails.organizationId(), fetchFails.schemaId()))
                .thenThrow(new ApiSchemaFetchException("HTTP 502"));
        when(revisionService.refresh(parseFails.connectorId(), parseFails.organizationId(), parseFails.schemaId()))
                .thenThrow(new ApiSchemaParseException("bad"));
        when(revisionService.refresh(crashes.connectorId(), crashes.organizationId(), crashes.schemaId()))
                .thenThrow(new IllegalStateException("boom"));
        refreshOf(healthy, ApiSchemaRefreshResult.unchangedResult());

        job.run();

        verify(revisionService).refresh(healthy.connectorId(), healthy.organizationId(), healthy.schemaId());
    }
}
