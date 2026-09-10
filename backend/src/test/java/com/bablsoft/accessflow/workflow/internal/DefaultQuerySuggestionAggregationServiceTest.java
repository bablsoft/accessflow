package com.bablsoft.accessflow.workflow.internal;

import com.bablsoft.accessflow.core.api.OrganizationAdminService;
import com.bablsoft.accessflow.core.api.OrganizationView;
import com.bablsoft.accessflow.core.api.PageResponse;
import com.bablsoft.accessflow.core.api.QuerySuggestionCorpusLookupService;
import com.bablsoft.accessflow.scheduling.api.DistributedLockService;
import com.bablsoft.accessflow.workflow.internal.persistence.repo.QuerySuggestionRepository;
import com.bablsoft.accessflow.workflow.internal.config.QuerySuggestionProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DefaultQuerySuggestionAggregationServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-10T12:00:00Z");
    private static final UUID ORG_A = UUID.randomUUID();
    private static final UUID ORG_B = UUID.randomUUID();
    private static final UUID DS_1 = UUID.randomUUID();
    private static final UUID DS_2 = UUID.randomUUID();

    private OrganizationAdminService organizationAdminService;
    private QuerySuggestionCorpusLookupService corpusLookupService;
    private QuerySuggestionDatasourceAggregator aggregator;
    private DistributedLockService distributedLockService;
    private QuerySuggestionRepository suggestionRepository;

    @BeforeEach
    void setUp() {
        organizationAdminService = mock(OrganizationAdminService.class);
        corpusLookupService = mock(QuerySuggestionCorpusLookupService.class);
        aggregator = mock(QuerySuggestionDatasourceAggregator.class);
        distributedLockService = mock(DistributedLockService.class);
        suggestionRepository = mock(QuerySuggestionRepository.class);
        when(suggestionRepository.findDatasourceIdsWithSuggestions(any())).thenReturn(List.of());
        // Default: the lock is free, so the action runs on the calling thread.
        when(distributedLockService.runLocked(anyString(), any(), any())).thenAnswer(inv -> {
            inv.getArgument(2, Runnable.class).run();
            return true;
        });
    }

    private DefaultQuerySuggestionAggregationService newService(boolean enabled) {
        var properties = new QuerySuggestionProperties(enabled, null, Duration.ofDays(90), 0, 0, 0,
                0, 0, null, 1, 1, 1, 10, 50, null);
        return new DefaultQuerySuggestionAggregationService(organizationAdminService,
                corpusLookupService, aggregator, distributedLockService, suggestionRepository,
                properties, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void disabledFeatureAggregatesNothing() {
        newService(false).aggregateAll();

        verify(organizationAdminService, never()).list(any());
        verify(aggregator, never()).aggregate(any(), any(), any());
    }

    @Test
    void everyOrganisationsDatasourcesAreAggregatedAgainstOneSharedRunStamp() {
        givenOrganizations(org(ORG_A, false), org(ORG_B, false));
        when(corpusLookupService.findDatasourceIdsWithHistory(eq(ORG_A), any()))
                .thenReturn(List.of(DS_1, DS_2));
        when(corpusLookupService.findDatasourceIdsWithHistory(eq(ORG_B), any()))
                .thenReturn(List.of(DS_1));

        newService(true).aggregateAll();

        verify(aggregator).aggregate(ORG_A, DS_1, NOW);
        verify(aggregator).aggregate(ORG_A, DS_2, NOW);
        verify(aggregator).aggregate(ORG_B, DS_1, NOW);
    }

    @Test
    void disabledOrganisationsAreSkipped() {
        givenOrganizations(org(ORG_A, true), org(ORG_B, false));
        when(corpusLookupService.findDatasourceIdsWithHistory(eq(ORG_B), any()))
                .thenReturn(List.of(DS_1));

        newService(true).aggregateAll();

        verify(corpusLookupService, never()).findDatasourceIdsWithHistory(eq(ORG_A), any());
        verify(aggregator).aggregate(ORG_B, DS_1, NOW);
    }

    @Test
    void oneFailingDatasourceDoesNotCostTheOthersTheirSuggestions() {
        givenOrganizations(org(ORG_A, false));
        when(corpusLookupService.findDatasourceIdsWithHistory(eq(ORG_A), any()))
                .thenReturn(List.of(DS_1, DS_2));
        doThrow(new IllegalStateException("engine plugin unavailable"))
                .when(aggregator).aggregate(ORG_A, DS_1, NOW);

        newService(true).aggregateAll();

        verify(aggregator).aggregate(ORG_A, DS_2, NOW);
    }

    @Test
    void oneFailingOrganisationDoesNotAbortTheBatch() {
        givenOrganizations(org(ORG_A, false), org(ORG_B, false));
        when(corpusLookupService.findDatasourceIdsWithHistory(eq(ORG_A), any()))
                .thenThrow(new IllegalStateException("read failed"));
        when(corpusLookupService.findDatasourceIdsWithHistory(eq(ORG_B), any()))
                .thenReturn(List.of(DS_1));

        newService(true).aggregateAll();

        verify(aggregator).aggregate(ORG_B, DS_1, NOW);
    }

    @Test
    void theLookbackIsMeasuredBackFromTheRunStamp() {
        givenOrganizations(org(ORG_A, false));
        when(corpusLookupService.findDatasourceIdsWithHistory(any(), any())).thenReturn(List.of());

        newService(true).aggregateAll();

        verify(corpusLookupService).findDatasourceIdsWithHistory(ORG_A,
                NOW.minus(Duration.ofDays(90)));
    }

    @Test
    void aDatasourceWhoseHistoryAgedOutIsStillVisitedSoItsSweepRuns() {
        givenOrganizations(org(ORG_A, false));
        // No qualifying history left, but rows are still being served.
        when(corpusLookupService.findDatasourceIdsWithHistory(eq(ORG_A), any()))
                .thenReturn(List.of());
        when(suggestionRepository.findDatasourceIdsWithSuggestions(ORG_A))
                .thenReturn(List.of(DS_1));

        newService(true).aggregateAll();

        verify(aggregator).aggregate(ORG_A, DS_1, NOW);
    }

    @Test
    void aDatasourceInBothSetsIsVisitedOnlyOnce() {
        givenOrganizations(org(ORG_A, false));
        when(corpusLookupService.findDatasourceIdsWithHistory(eq(ORG_A), any()))
                .thenReturn(List.of(DS_1, DS_2));
        when(suggestionRepository.findDatasourceIdsWithSuggestions(ORG_A))
                .thenReturn(List.of(DS_1));

        newService(true).aggregateAll();

        verify(aggregator, times(1)).aggregate(ORG_A, DS_1, NOW);
        verify(aggregator, times(1)).aggregate(ORG_A, DS_2, NOW);
    }

    @Test
    void eachDatasourceIsRebuiltUnderTheSameLockTheOnDemandRecomputeTakes() {
        givenOrganizations(org(ORG_A, false));
        when(corpusLookupService.findDatasourceIdsWithHistory(eq(ORG_A), any()))
                .thenReturn(List.of(DS_1));

        newService(true).aggregateAll();

        verify(distributedLockService).runLocked(eq("querySuggestionRebuild:" + DS_1), any(),
                any());
    }

    @Test
    void aDatasourceAlreadyBeingRebuiltElsewhereIsSkippedWithoutFailing() {
        givenOrganizations(org(ORG_A, false));
        when(corpusLookupService.findDatasourceIdsWithHistory(eq(ORG_A), any()))
                .thenReturn(List.of(DS_1, DS_2));
        when(distributedLockService.runLocked(eq("querySuggestionRebuild:" + DS_1), any(), any()))
                .thenReturn(false);

        newService(true).aggregateAll();

        verify(aggregator, never()).aggregate(ORG_A, DS_1, NOW);
        verify(aggregator).aggregate(ORG_A, DS_2, NOW);
    }

    @Test
    void theOnDemandPathDoesNotReacquireTheLockItsCallerAlreadyHolds() {
        newService(true).aggregateDatasource(ORG_A, DS_1);

        verify(aggregator).aggregate(ORG_A, DS_1, NOW);
        verify(distributedLockService, never()).runLocked(anyString(), any(), any());
    }

    @Test
    void onDemandRecomputeRunsExactlyOneDatasource() {
        assertThat(newService(true).aggregateDatasource(ORG_A, DS_1)).isTrue();

        verify(aggregator).aggregate(ORG_A, DS_1, NOW);
        verify(organizationAdminService, never()).list(any());
    }

    @Test
    void onDemandRecomputeIsANoOpWhenTheFeatureIsDisabled() {
        assertThat(newService(false).aggregateDatasource(ORG_A, DS_1)).isFalse();

        verify(aggregator, never()).aggregate(any(), any(), any());
    }

    private void givenOrganizations(OrganizationView... organizations) {
        when(organizationAdminService.list(any()))
                .thenReturn(new PageResponse<>(List.of(organizations), 0, 200,
                        organizations.length, 1));
    }

    private static OrganizationView org(UUID id, boolean disabled) {
        return new OrganizationView(id, "org-" + id, "slug-" + id, disabled, null, null,
                null, false, false, null, null);
    }
}
