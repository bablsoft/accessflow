package com.bablsoft.accessflow.audit.internal;

import com.bablsoft.accessflow.audit.api.GrantUsageAuditAggregationService;
import com.bablsoft.accessflow.audit.internal.persistence.entity.AuditLogEntity;
import com.bablsoft.accessflow.audit.internal.persistence.repo.GrantUsageAuditRepository;
import com.bablsoft.accessflow.core.api.GrantResourceKind;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DefaultGrantUsageAuditAggregationServiceTest {

    private static final UUID ORG = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();
    private static final UUID DATASOURCE = UUID.randomUUID();
    private static final UUID CONNECTOR = UUID.randomUUID();
    private static final Instant FROM = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant TO = Instant.parse("2026-01-02T00:00:00Z");
    private static final Instant AT = Instant.parse("2026-01-01T09:15:00Z");

    private final GrantUsageAuditRepository repo = mock(GrantUsageAuditRepository.class);
    private final DefaultGrantUsageAuditAggregationService service =
            new DefaultGrantUsageAuditAggregationService(repo, new ObjectMapper());

    private static AuditLogEntity row(String action, String metadata) {
        var entity = new AuditLogEntity();
        entity.setId(UUID.randomUUID());
        entity.setOrganizationId(ORG);
        entity.setActorId(USER);
        entity.setAction(action);
        entity.setMetadata(metadata);
        entity.setCreatedAt(AT);
        return entity;
    }

    private void given(AuditLogEntity... rows) {
        when(repo.findUsageEvents(eq(ORG), anyCollection(), eq(FROM), any(), eq(TO),
                any(Pageable.class))).thenReturn(List.of(rows));
    }

    private List<com.bablsoft.accessflow.audit.api.GrantUsageAuditEvent> events() {
        return service.findUsageEvents(ORG, FROM, GrantUsageAuditAggregationService.START, TO, 100);
    }

    @Test
    void mapsExecutedQueryToDatasourceEventWithReferencedTables() {
        given(row("QUERY_EXECUTED", """
                {"datasource_id":"%s","referenced_tables":["public.users","public.orders"]}
                """.formatted(DATASOURCE)));

        assertThat(events()).singleElement().satisfies(event -> {
            assertThat(event.organizationId()).isEqualTo(ORG);
            assertThat(event.userId()).isEqualTo(USER);
            assertThat(event.resourceKind()).isEqualTo(GrantResourceKind.DATASOURCE);
            assertThat(event.resourceId()).isEqualTo(DATASOURCE);
            assertThat(event.occurredAt()).isEqualTo(AT);
            assertThat(event.targets()).containsExactly("public.users", "public.orders");
        });
    }

    @Test
    void countsBreakGlassExecutionAsGrantUsage() {
        given(row("QUERY_BREAK_GLASS_EXECUTED", """
                {"datasource_id":"%s","referenced_tables":["public.audit"],"break_glass":true}
                """.formatted(DATASOURCE)));

        assertThat(events()).singleElement().satisfies(event -> {
            assertThat(event.resourceKind()).isEqualTo(GrantResourceKind.DATASOURCE);
            assertThat(event.targets()).containsExactly("public.audit");
        });
    }

    @Test
    void mapsExecutedApiRequestToConnectorEventWithOperation() {
        given(row("API_REQUEST_EXECUTED", """
                {"connector_id":"%s","path":"/v1/pets","operation_id":"listPets"}
                """.formatted(CONNECTOR)));

        assertThat(events()).singleElement().satisfies(event -> {
            assertThat(event.resourceKind()).isEqualTo(GrantResourceKind.API_CONNECTOR);
            assertThat(event.resourceId()).isEqualTo(CONNECTOR);
            assertThat(event.targets()).containsExactly("listPets");
        });
    }

    @Test
    void countsApiBreakGlassExecutionAsGrantUsage() {
        given(row("API_REQUEST_BREAK_GLASS_EXECUTED", """
                {"connector_id":"%s","operation_id":"deletePet"}
                """.formatted(CONNECTOR)));

        assertThat(events()).singleElement()
                .satisfies(event -> assertThat(event.targets()).containsExactly("deletePet"));
    }

    /**
     * Rows predating the #625 operation_id enrichment must still count as use of the grant, with no
     * targets — otherwise an unenriched history would make every connector grant look over-scoped.
     */
    @Test
    void connectorEventWithoutOperationIdCountsAsUsageWithNoTargets() {
        given(row("API_REQUEST_EXECUTED", """
                {"connector_id":"%s","path":"/v1/pets"}
                """.formatted(CONNECTOR)));

        assertThat(events()).singleElement().satisfies(event -> {
            assertThat(event.resourceId()).isEqualTo(CONNECTOR);
            assertThat(event.targets()).isEmpty();
        });
    }

    @Test
    void skipsRowsWithUnparseableMetadata() {
        given(row("QUERY_EXECUTED", "{not json"));

        assertThat(events()).isEmpty();
    }

    @Test
    void skipsRowsWithMissingOrMalformedResourceId() {
        given(row("QUERY_EXECUTED", "{\"referenced_tables\":[\"t\"]}"),
                row("QUERY_EXECUTED", "{\"datasource_id\":\"not-a-uuid\"}"),
                row("API_REQUEST_EXECUTED", "{\"path\":\"/v1/pets\"}"));

        assertThat(events()).isEmpty();
    }

    @Test
    void requestsOnlyTheFourExecutionActions() {
        given();

        events();

        verify(repo).findUsageEvents(eq(ORG),
                eq(List.of("QUERY_EXECUTED", "QUERY_BREAK_GLASS_EXECUTED", "API_REQUEST_EXECUTED",
                        "API_REQUEST_BREAK_GLASS_EXECUTED")),
                eq(FROM), any(), eq(TO), any(Pageable.class));
    }

    @Test
    void capsTheReadAtMaxRows() {
        when(repo.findUsageEvents(eq(ORG), anyCollection(), eq(FROM), any(), eq(TO),
                any(Pageable.class))).thenReturn(List.of());

        service.findUsageEvents(ORG, FROM, GrantUsageAuditAggregationService.START, TO, 25);

        verify(repo).findUsageEvents(eq(ORG), anyCollection(), eq(FROM), any(), eq(TO),
                eq(org.springframework.data.domain.PageRequest.of(0, 25)));
    }

    /** The nil UUID is the "start of this instant" sentinel; a null keyset must fall back to it. */
    @Test
    void aNullKeysetStartsAtTheBeginningOfTheInstant() {
        when(repo.findUsageEvents(eq(ORG), anyCollection(), eq(FROM), any(), eq(TO),
                any(Pageable.class))).thenReturn(List.of());

        service.findUsageEvents(ORG, FROM, null, TO, 10);

        verify(repo).findUsageEvents(eq(ORG), anyCollection(), eq(FROM),
                eq(GrantUsageAuditAggregationService.START), eq(TO), any(Pageable.class));
    }

    @Test
    void returnsEmptyForAnEmptyOrInvertedWindowWithoutQuerying() {
        var start = GrantUsageAuditAggregationService.START;
        assertThat(service.findUsageEvents(ORG, TO, start, FROM, 10)).isEmpty();
        assertThat(service.findUsageEvents(ORG, FROM, start, FROM, 10)).isEmpty();
        assertThat(service.findUsageEvents(ORG, null, start, TO, 10)).isEmpty();

        verify(repo, never()).findUsageEvents(any(), anyCollection(), any(), any(), any(), any());
    }

    @Test
    void rejectsMissingOrganizationAndNonPositiveMaxRows() {
        assertThatThrownBy(() -> service.findUsageEvents(null, FROM,
                GrantUsageAuditAggregationService.START, TO, 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("organizationId");
        assertThatThrownBy(() -> service.findUsageEvents(ORG, FROM,
                GrantUsageAuditAggregationService.START, TO, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxRows");
    }
}
