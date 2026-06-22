package com.bablsoft.accessflow.audit.internal;

import com.bablsoft.accessflow.audit.internal.persistence.entity.AuditLogEntity;
import com.bablsoft.accessflow.audit.internal.persistence.repo.BehaviorAuditRepository;
import com.bablsoft.accessflow.audit.internal.persistence.repo.BehaviorAuditRepository.SubjectProjection;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DefaultBehaviorAuditAggregationServiceTest {

    private static final UUID ORG = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();
    private static final UUID DS = UUID.randomUUID();
    private static final Instant FROM = Instant.parse("2026-01-01T11:00:00Z");
    private static final Instant TO = Instant.parse("2026-01-01T12:00:00Z");

    private final BehaviorAuditRepository repo = mock(BehaviorAuditRepository.class);
    private final tools.jackson.databind.ObjectMapper objectMapper =
            new tools.jackson.databind.ObjectMapper();
    private final DefaultBehaviorAuditAggregationService service =
            new DefaultBehaviorAuditAggregationService(repo, objectMapper);

    private static SubjectProjection projection(UUID org, UUID user, UUID ds) {
        var p = mock(SubjectProjection.class);
        when(p.getOrganizationId()).thenReturn(org);
        when(p.getUserId()).thenReturn(user);
        when(p.getDatasourceId()).thenReturn(ds);
        return p;
    }

    private static AuditLogEntity row(String action, String metadata) {
        var e = new AuditLogEntity();
        e.setId(UUID.randomUUID());
        e.setAction(action);
        e.setMetadata(metadata);
        e.setCreatedAt(Instant.parse("2026-01-01T11:30:00Z"));
        return e;
    }

    @Test
    void findActiveSubjectsMapsProjectionsToRefs() {
        var user2 = UUID.randomUUID();
        // Build the projection mocks first — nesting their own when(...) inside the outer
        // when(repo...) call triggers Mockito's UnfinishedStubbingException.
        var p1 = projection(ORG, USER, DS);
        var p2 = projection(ORG, user2, DS);
        when(repo.findActiveSubjects(FROM, TO)).thenReturn(List.of(p1, p2));

        var subjects = service.findActiveSubjects(FROM, TO);

        assertThat(subjects).hasSize(2);
        assertThat(subjects.get(0).organizationId()).isEqualTo(ORG);
        assertThat(subjects.get(0).userId()).isEqualTo(USER);
        assertThat(subjects.get(0).datasourceId()).isEqualTo(DS);
        assertThat(subjects.get(1).userId()).isEqualTo(user2);
    }

    @Test
    void findActiveSubjectsReturnsEmptyForNoRows() {
        when(repo.findActiveSubjects(FROM, TO)).thenReturn(List.of());
        assertThat(service.findActiveSubjects(FROM, TO)).isEmpty();
    }

    @Test
    void samplesForExtractsAllFieldsFromExecutedRow() {
        var metadata = """
                {"datasource_id":"%s","query_type":"SELECT",
                 "referenced_tables":["orders","users"],"rows_returned":42}
                """.formatted(DS);
        stubRows(List.of(row("QUERY_EXECUTED", metadata)));

        var samples = service.samplesFor(ORG, USER, DS, FROM, TO);

        assertThat(samples).hasSize(1);
        var s = samples.get(0);
        assertThat(s.success()).isTrue();
        assertThat(s.queryType()).isEqualTo("SELECT");
        assertThat(s.referencedTables()).containsExactly("orders", "users");
        assertThat(s.rowsReturned()).isEqualTo(42L);
        assertThat(s.occurredAt()).isEqualTo(Instant.parse("2026-01-01T11:30:00Z"));
    }

    @Test
    void samplesForMarksFailedRowsAsUnsuccessful() {
        var metadata = "{\"datasource_id\":\"%s\",\"query_type\":\"DELETE\"}".formatted(DS);
        stubRows(List.of(row("QUERY_FAILED", metadata)));

        var samples = service.samplesFor(ORG, USER, DS, FROM, TO);

        assertThat(samples).hasSize(1);
        assertThat(samples.get(0).success()).isFalse();
        assertThat(samples.get(0).queryType()).isEqualTo("DELETE");
        assertThat(samples.get(0).referencedTables()).isEmpty();
        assertThat(samples.get(0).rowsReturned()).isNull();
    }

    @Test
    void samplesForFiltersOutNonMatchingDatasource() {
        var otherDs = UUID.randomUUID();
        var metadata = "{\"datasource_id\":\"%s\",\"query_type\":\"SELECT\"}".formatted(otherDs);
        stubRows(List.of(row("QUERY_EXECUTED", metadata)));

        assertThat(service.samplesFor(ORG, USER, DS, FROM, TO)).isEmpty();
    }

    @Test
    void samplesForFiltersOutRowsWithMissingDatasourceId() {
        stubRows(List.of(row("QUERY_EXECUTED", "{\"query_type\":\"SELECT\"}")));
        assertThat(service.samplesFor(ORG, USER, DS, FROM, TO)).isEmpty();
    }

    @Test
    void samplesForSkipsRowsWithNullMetadata() {
        stubRows(List.of(row("QUERY_EXECUTED", null)));
        assertThat(service.samplesFor(ORG, USER, DS, FROM, TO)).isEmpty();
    }

    @Test
    void samplesForSkipsRowsWithBlankMetadata() {
        stubRows(List.of(row("QUERY_EXECUTED", "   ")));
        assertThat(service.samplesFor(ORG, USER, DS, FROM, TO)).isEmpty();
    }

    @Test
    void samplesForSkipsRowsWithGarbageMetadata() {
        stubRows(List.of(row("QUERY_EXECUTED", "{ not json ]")));
        assertThat(service.samplesFor(ORG, USER, DS, FROM, TO)).isEmpty();
    }

    @Test
    void samplesForLeavesOptionalFieldsNullWhenAbsent() {
        var metadata = "{\"datasource_id\":\"%s\"}".formatted(DS);
        stubRows(List.of(row("QUERY_EXECUTED", metadata)));

        var samples = service.samplesFor(ORG, USER, DS, FROM, TO);

        assertThat(samples).hasSize(1);
        assertThat(samples.get(0).queryType()).isNull();
        assertThat(samples.get(0).referencedTables()).isEmpty();
        assertThat(samples.get(0).rowsReturned()).isNull();
    }

    @Test
    void samplesForIgnoresNonStringTablesAndNonNumericRows() {
        var metadata = """
                {"datasource_id":"%s","referenced_tables":["good",123,null],
                 "rows_returned":"not-a-number","query_type":42}
                """.formatted(DS);
        stubRows(List.of(row("QUERY_EXECUTED", metadata)));

        var samples = service.samplesFor(ORG, USER, DS, FROM, TO);

        assertThat(samples).hasSize(1);
        // Only the string element survives the referenced_tables filter.
        assertThat(samples.get(0).referencedTables()).containsExactly("good");
        // rows_returned is a string → not a number → null.
        assertThat(samples.get(0).rowsReturned()).isNull();
        // query_type is numeric → textOrNull returns null.
        assertThat(samples.get(0).queryType()).isNull();
    }

    @Test
    void samplesForIgnoresNonArrayReferencedTables() {
        var metadata = "{\"datasource_id\":\"%s\",\"referenced_tables\":\"orders\"}".formatted(DS);
        stubRows(List.of(row("QUERY_EXECUTED", metadata)));

        var samples = service.samplesFor(ORG, USER, DS, FROM, TO);

        assertThat(samples).hasSize(1);
        assertThat(samples.get(0).referencedTables()).isEmpty();
    }

    @Test
    void samplesForReturnsEmptyWhenNoRows() {
        stubRows(List.of());
        assertThat(service.samplesFor(ORG, USER, DS, FROM, TO)).isEmpty();
    }

    private void stubRows(List<AuditLogEntity> rows) {
        when(repo.findByOrganizationIdAndActorIdAndActionInAndCreatedAtGreaterThanEqualAndCreatedAtLessThanOrderByCreatedAtAsc(
                eq(ORG), eq(USER), any(), eq(FROM), eq(TO))).thenReturn(rows);
    }
}
