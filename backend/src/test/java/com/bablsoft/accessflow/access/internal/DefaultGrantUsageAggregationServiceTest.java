package com.bablsoft.accessflow.access.internal;

import com.bablsoft.accessflow.access.api.GrantUsageRecommendation;
import com.bablsoft.accessflow.access.events.GrantStaleEvent;
import com.bablsoft.accessflow.access.internal.config.AccessProperties;
import com.bablsoft.accessflow.access.internal.persistence.entity.GrantUsageSummaryEntity;
import com.bablsoft.accessflow.access.internal.persistence.entity.GrantUsageWatermarkEntity;
import com.bablsoft.accessflow.access.internal.persistence.repo.GrantUsageSummaryRepository;
import com.bablsoft.accessflow.access.internal.persistence.repo.GrantUsageWatermarkRepository;
import com.bablsoft.accessflow.apigov.api.ApiConnectorAdminService;
import com.bablsoft.accessflow.apigov.api.ApiConnectorLookupService;
import com.bablsoft.accessflow.apigov.api.ApiConnectorPermissionView;
import com.bablsoft.accessflow.apigov.api.ApiConnectorRef;
import com.bablsoft.accessflow.apigov.api.ApiProtocol;
import com.bablsoft.accessflow.audit.api.GrantUsageAuditAggregationService;
import com.bablsoft.accessflow.audit.api.GrantUsageAuditEvent;
import com.bablsoft.accessflow.core.api.DatasourceAdminService;
import com.bablsoft.accessflow.core.api.DatasourceLookupService;
import com.bablsoft.accessflow.core.api.DatasourcePermissionView;
import com.bablsoft.accessflow.core.api.DatasourceRef;
import com.bablsoft.accessflow.core.api.GrantResourceKind;
import com.bablsoft.accessflow.core.api.OrganizationAdminService;
import com.bablsoft.accessflow.core.api.OrganizationView;
import com.bablsoft.accessflow.core.api.PageResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DefaultGrantUsageAggregationServiceTest {

    private static final UUID ORG = UUID.randomUUID();
    private static final UUID DATASOURCE = UUID.randomUUID();
    private static final UUID CONNECTOR = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();
    private static final UUID PERMISSION = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-06-01T00:00:00Z");

    private final GrantUsageSummaryRepository summaryRepository =
            mock(GrantUsageSummaryRepository.class);
    private final GrantUsageWatermarkRepository watermarkRepository =
            mock(GrantUsageWatermarkRepository.class);
    private final GrantUsageAuditAggregationService auditAggregationService =
            mock(GrantUsageAuditAggregationService.class);
    private final OrganizationAdminService organizationAdminService =
            mock(OrganizationAdminService.class);
    private final DatasourceLookupService datasourceLookupService =
            mock(DatasourceLookupService.class);
    private final DatasourceAdminService datasourceAdminService = mock(DatasourceAdminService.class);
    private final ApiConnectorLookupService connectorLookupService =
            mock(ApiConnectorLookupService.class);
    private final ApiConnectorAdminService connectorAdminService =
            mock(ApiConnectorAdminService.class);
    private final ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
    private final GrantUsageViewMapper viewMapper = new GrantUsageViewMapper(new ObjectMapper());

    private final List<GrantUsageSummaryEntity> stored = new ArrayList<>();
    private AccessProperties properties;
    private DefaultGrantUsageAggregationService service;

    @BeforeEach
    void setUp() {
        properties = new AccessProperties(null, null, null, null);
        service = build();
        lenient().when(summaryRepository.findByOrganizationId(ORG)).thenReturn(stored);
        lenient().when(watermarkRepository.findById(ORG)).thenReturn(Optional.empty());
        lenient().when(datasourceLookupService.findActiveRefsByOrganization(ORG))
                .thenReturn(List.of());
        lenient().when(connectorLookupService.findActiveRefsByOrganization(ORG))
                .thenReturn(List.of());
        lenient().when(auditAggregationService.findUsageEvents(eq(ORG), any(), any(), anyInt()))
                .thenReturn(List.of());
    }

    private DefaultGrantUsageAggregationService build() {
        return new DefaultGrantUsageAggregationService(summaryRepository, watermarkRepository,
                auditAggregationService, organizationAdminService, datasourceLookupService,
                datasourceAdminService, connectorLookupService, connectorAdminService, viewMapper,
                properties, eventPublisher);
    }

    private void withUsage(AccessProperties.Usage usage) {
        properties = new AccessProperties(null, null, null, usage);
        service = build();
    }

    // ------------------------------------------------------------------ fixtures

    private void givenDatasourceGrant(List<String> allowedTables, Instant grantedAt) {
        when(datasourceLookupService.findActiveRefsByOrganization(ORG))
                .thenReturn(List.of(new DatasourceRef(DATASOURCE, "analytics")));
        when(datasourceAdminService.listPermissions(DATASOURCE, ORG)).thenReturn(List.of(
                new DatasourcePermissionView(PERMISSION, DATASOURCE, USER, "dev@example.test",
                        "Dev", true, false, false, false, null, List.of(), allowedTables, List.of(),
                        null, UUID.randomUUID(), grantedAt)));
    }

    private void givenConnectorGrant(List<String> allowedOperations) {
        when(connectorLookupService.findActiveRefsByOrganization(ORG))
                .thenReturn(List.of(new ApiConnectorRef(CONNECTOR, "billing", ApiProtocol.REST, null)));
        when(connectorAdminService.listPermissions(CONNECTOR, ORG)).thenReturn(List.of(
                new ApiConnectorPermissionView(PERMISSION, CONNECTOR, USER, "dev@example.test",
                        "Dev", true, false, false, false, null, allowedOperations, List.of(),
                        NOW.minus(Duration.ofDays(200)))));
    }

    private void givenUsage(GrantUsageAuditEvent... events) {
        when(auditAggregationService.findUsageEvents(eq(ORG), any(), any(), anyInt()))
                .thenReturn(List.of(events));
    }

    private static GrantUsageAuditEvent datasourceUse(Instant at, String... tables) {
        return new GrantUsageAuditEvent(ORG, USER, GrantResourceKind.DATASOURCE, DATASOURCE, at,
                List.of(tables));
    }

    private List<GrantUsageSummaryEntity> saved() {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Iterable<GrantUsageSummaryEntity>> captor =
                ArgumentCaptor.forClass(Iterable.class);
        verify(summaryRepository).saveAll(captor.capture());
        var rows = new ArrayList<GrantUsageSummaryEntity>();
        captor.getValue().forEach(rows::add);
        return rows;
    }

    private GrantUsageSummaryEntity onlySaved() {
        var rows = saved();
        assertThat(rows).hasSize(1);
        return rows.get(0);
    }

    // ------------------------------------------------------------------ organizations

    @Test
    void enumeratesOrganizationsSortedByIdAndSkipsDisabledTenants() {
        var enabled = UUID.randomUUID();
        var disabled = UUID.randomUUID();
        when(organizationAdminService.list(any())).thenReturn(new PageResponse<>(List.of(
                new OrganizationView(enabled, "A", "a", false, null, null, null, NOW, NOW),
                new OrganizationView(disabled, "B", "b", true, null, null, null, NOW, NOW)),
                0, 100, 2, 1));

        assertThat(service.findOrganizationIds()).containsExactly(enabled);

        var captor = ArgumentCaptor.forClass(com.bablsoft.accessflow.core.api.PageRequest.class);
        verify(organizationAdminService).list(captor.capture());
        assertThat(captor.getValue().sort()).singleElement()
                .satisfies(order -> assertThat(order.property()).isEqualTo("id"));
    }

    // ------------------------------------------------------------------ reconcile

    @Test
    void createsASummaryPerLiveDatasourceAndConnectorGrant() {
        givenDatasourceGrant(List.of("public.users"), NOW.minus(Duration.ofDays(200)));
        givenConnectorGrant(List.of("listInvoices"));

        assertThat(service.aggregateOrganization(ORG, NOW)).isEqualTo(2);
        assertThat(saved()).extracting(GrantUsageSummaryEntity::getResourceKind)
                .containsExactlyInAnyOrder(GrantResourceKind.DATASOURCE,
                        GrantResourceKind.API_CONNECTOR);
    }

    /** A revoked grant must vanish from the report, not linger as a permanently idle row. */
    @Test
    void deletesSummariesWhoseGrantIsGone() {
        var orphan = summaryRow(GrantResourceKind.DATASOURCE, DATASOURCE, USER);
        stored.add(orphan);

        service.aggregateOrganization(ORG, NOW);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Iterable<GrantUsageSummaryEntity>> captor =
                ArgumentCaptor.forClass(Iterable.class);
        verify(summaryRepository).deleteAll(captor.capture());
        assertThat(captor.getValue()).containsExactly(orphan);
    }

    /** Empty allow-list means unrestricted, which is null — not zero, which would divide. */
    @Test
    void anUnrestrictedGrantRecordsANullGrantedTargetCount() {
        givenDatasourceGrant(List.of(), NOW.minus(Duration.ofDays(200)));

        service.aggregateOrganization(ORG, NOW);

        assertThat(onlySaved().getGrantedTargetCount()).isNull();
    }

    @Test
    void aScopeLimitedGrantRecordsItsAllowListSize() {
        givenDatasourceGrant(List.of("public.users", "public.orders"), NOW.minus(Duration.ofDays(200)));

        service.aggregateOrganization(ORG, NOW);

        assertThat(onlySaved().getGrantedTargetCount()).isEqualTo(2);
    }

    /** Observation cannot start before the grant existed — otherwise frequency is understated. */
    @Test
    void observationStartsAtTheLaterOfGrantCreationAndTheBackfillWindow() {
        givenDatasourceGrant(List.of(), NOW.minus(Duration.ofDays(5)));

        service.aggregateOrganization(ORG, NOW);

        assertThat(onlySaved().getObservedSince()).isEqualTo(NOW.minus(Duration.ofDays(5)));
    }

    @Test
    void observationIsCappedAtTheBackfillWindowForAnOldGrant() {
        givenDatasourceGrant(List.of(), NOW.minus(Duration.ofDays(400)));

        service.aggregateOrganization(ORG, NOW);

        assertThat(onlySaved().getObservedSince()).isEqualTo(NOW.minus(Duration.ofDays(90)));
    }

    // ------------------------------------------------------------------ fold

    @Test
    void foldsUsageIntoCountsAndTimestamps() {
        givenDatasourceGrant(List.of("public.users", "public.orders", "public.items", "public.logs"),
                NOW.minus(Duration.ofDays(200)));
        givenUsage(datasourceUse(NOW.minus(Duration.ofDays(10)), "public.users"),
                datasourceUse(NOW.minus(Duration.ofDays(2)), "public.users"));

        service.aggregateOrganization(ORG, NOW);

        var row = onlySaved();
        assertThat(row.getUsageCount()).isEqualTo(2);
        assertThat(row.getFirstUsedAt()).isEqualTo(NOW.minus(Duration.ofDays(10)));
        assertThat(row.getLastUsedAt()).isEqualTo(NOW.minus(Duration.ofDays(2)));
        // One of four granted tables ever touched — 0.25, below the 0.5 threshold.
        assertThat(row.getUsedTargetCount()).isEqualTo(1);
        assertThat(row.getRecommendation()).isEqualTo(GrantUsageRecommendation.OVER_SCOPED);
    }

    /** Exactly at the ratio threshold is not below it, so a half-used grant stays ACTIVE. */
    @Test
    void aGrantExercisingExactlyTheThresholdFractionIsActive() {
        givenDatasourceGrant(List.of("public.users", "public.orders"), NOW.minus(Duration.ofDays(200)));
        givenUsage(datasourceUse(NOW.minus(Duration.ofDays(2)), "public.users"));

        service.aggregateOrganization(ORG, NOW);

        assertThat(onlySaved().getRecommendation()).isEqualTo(GrantUsageRecommendation.ACTIVE);
    }

    /** Usage with no matching live grant has nothing to attribute to and must be dropped. */
    @Test
    void ignoresUsageForAGrantThatNoLongerExists() {
        givenUsage(datasourceUse(NOW.minus(Duration.ofDays(1)), "public.users"));

        assertThat(service.aggregateOrganization(ORG, NOW)).isZero();
        assertThat(saved()).isEmpty();
    }

    @Test
    void capsTheTrackedTargetSet() {
        withUsage(new AccessProperties.Usage(null, null, null, null, 0, 0, 2, 0, null, null));
        givenDatasourceGrant(List.of("a", "b", "c", "d"), NOW.minus(Duration.ofDays(200)));
        givenUsage(datasourceUse(NOW.minus(Duration.ofDays(1)), "a", "b", "c", "d"));

        service.aggregateOrganization(ORG, NOW);

        assertThat(viewMapper.readUsedTargets(onlySaved())).hasSize(2);
    }

    // ------------------------------------------------------------------ watermark

    @Test
    void advancesTheCursorToTheWindowEndWhenTheWindowIsDrained() {
        givenDatasourceGrant(List.of(), NOW.minus(Duration.ofDays(200)));

        service.aggregateOrganization(ORG, NOW);

        var captor = ArgumentCaptor.forClass(GrantUsageWatermarkEntity.class);
        verify(watermarkRepository).save(captor.capture());
        assertThat(captor.getValue().getAggregatedThrough()).isEqualTo(NOW);
    }

    /**
     * A full page means the window was not drained. Resuming from the window end would silently
     * skip everything after the last event read, so the cursor must stop at that event.
     */
    @Test
    void aFullPageResumesFromTheLastEventRatherThanTheWindowEnd() {
        withUsage(new AccessProperties.Usage(null, null, null, null, 0, 2, 0, 0, null, null));
        givenDatasourceGrant(List.of(), NOW.minus(Duration.ofDays(200)));
        var last = NOW.minus(Duration.ofDays(20));
        givenUsage(datasourceUse(NOW.minus(Duration.ofDays(30)), "a"), datasourceUse(last, "b"));

        service.aggregateOrganization(ORG, NOW);

        var captor = ArgumentCaptor.forClass(GrantUsageWatermarkEntity.class);
        verify(watermarkRepository).save(captor.capture());
        assertThat(captor.getValue().getAggregatedThrough()).isEqualTo(last.plusMillis(1));
    }

    @Test
    void foldsOnlyFromTheStoredCursorForward() {
        var cursor = NOW.minus(Duration.ofDays(3));
        var watermark = new GrantUsageWatermarkEntity();
        watermark.setOrganizationId(ORG);
        watermark.setAggregatedThrough(cursor);
        when(watermarkRepository.findById(ORG)).thenReturn(Optional.of(watermark));
        stored.add(summaryRow(GrantResourceKind.DATASOURCE, DATASOURCE, USER));
        givenDatasourceGrant(List.of(), NOW.minus(Duration.ofDays(200)));

        service.aggregateOrganization(ORG, NOW);

        verify(auditAggregationService).findUsageEvents(ORG, cursor, NOW,
                properties.usage().maxRowsPerTick());
    }

    /**
     * A grant summarised for the first time after the cursor has moved on would read as never-used
     * forever without an explicit backfill over its own observation window.
     */
    @Test
    void backfillsANewSummaryOverTheWindowThatTheCursorAlreadyPassed() {
        var cursor = NOW.minus(Duration.ofDays(1));
        var watermark = new GrantUsageWatermarkEntity();
        watermark.setOrganizationId(ORG);
        watermark.setAggregatedThrough(cursor);
        when(watermarkRepository.findById(ORG)).thenReturn(Optional.of(watermark));
        givenDatasourceGrant(List.of(), NOW.minus(Duration.ofDays(200)));

        service.aggregateOrganization(ORG, NOW);

        verify(auditAggregationService).findUsageEvents(ORG, NOW.minus(Duration.ofDays(90)), cursor,
                properties.usage().maxRowsPerTick());
    }

    @Test
    void doesNotBackfillAnExistingSummary() {
        var cursor = NOW.minus(Duration.ofDays(1));
        var watermark = new GrantUsageWatermarkEntity();
        watermark.setOrganizationId(ORG);
        watermark.setAggregatedThrough(cursor);
        when(watermarkRepository.findById(ORG)).thenReturn(Optional.of(watermark));
        stored.add(summaryRow(GrantResourceKind.DATASOURCE, DATASOURCE, USER));
        givenDatasourceGrant(List.of(), NOW.minus(Duration.ofDays(200)));

        service.aggregateOrganization(ORG, NOW);

        verify(auditAggregationService, never()).findUsageEvents(eq(ORG),
                eq(NOW.minus(Duration.ofDays(90))), eq(cursor), anyInt());
    }

    // ------------------------------------------------------------------ nudge

    @Test
    void publishesAStaleNudgeForANeverUsedGrant() {
        givenDatasourceGrant(List.of(), NOW.minus(Duration.ofDays(200)));

        service.aggregateOrganization(ORG, NOW);

        var captor = ArgumentCaptor.forClass(GrantStaleEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().recommendation())
                .isEqualTo(GrantUsageRecommendation.NEVER_USED);
        assertThat(captor.getValue().daysSinceLastUse()).isNull();
        assertThat(onlySaved().getNudgedAt()).isEqualTo(NOW);
    }

    @Test
    void doesNotNudgeAgainWithinTheCooldown() {
        var row = summaryRow(GrantResourceKind.DATASOURCE, DATASOURCE, USER);
        row.setNudgedAt(NOW.minus(Duration.ofDays(1)));
        stored.add(row);
        givenDatasourceGrant(List.of(), NOW.minus(Duration.ofDays(200)));

        service.aggregateOrganization(ORG, NOW);

        verify(eventPublisher, never()).publishEvent(any(GrantStaleEvent.class));
    }

    @Test
    void nudgesAgainOnceTheCooldownHasElapsed() {
        var row = summaryRow(GrantResourceKind.DATASOURCE, DATASOURCE, USER);
        row.setNudgedAt(NOW.minus(Duration.ofDays(40)));
        stored.add(row);
        givenDatasourceGrant(List.of(), NOW.minus(Duration.ofDays(200)));

        service.aggregateOrganization(ORG, NOW);

        verify(eventPublisher).publishEvent(any(GrantStaleEvent.class));
    }

    @Test
    void doesNotNudgeWhenDisabled() {
        withUsage(new AccessProperties.Usage(null, null, null, null, 0, 0, 0, 0, Boolean.FALSE, null));
        givenDatasourceGrant(List.of(), NOW.minus(Duration.ofDays(200)));

        service.aggregateOrganization(ORG, NOW);

        verify(eventPublisher, never()).publishEvent(any(GrantStaleEvent.class));
    }

    @Test
    void doesNotNudgeAnActiveGrant() {
        givenDatasourceGrant(List.of(), NOW.minus(Duration.ofDays(200)));
        givenUsage(datasourceUse(NOW.minus(Duration.ofDays(1))));

        service.aggregateOrganization(ORG, NOW);

        verify(eventPublisher, never()).publishEvent(any(GrantStaleEvent.class));
    }

    private static GrantUsageSummaryEntity summaryRow(GrantResourceKind kind, UUID resourceId,
                                                      UUID userId) {
        var row = new GrantUsageSummaryEntity();
        row.setId(UUID.randomUUID());
        row.setOrganizationId(ORG);
        row.setResourceKind(kind);
        row.setResourceId(resourceId);
        row.setResourceName("analytics");
        row.setPermissionId(PERMISSION);
        row.setUserId(userId);
        row.setUserEmail("dev@example.test");
        row.setGrantedAt(NOW.minus(Duration.ofDays(200)));
        row.setObservedSince(NOW.minus(Duration.ofDays(90)));
        return row;
    }
}
