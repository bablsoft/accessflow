package com.bablsoft.accessflow.workflow.internal;

import com.bablsoft.accessflow.core.api.DatabaseSchemaView;
import com.bablsoft.accessflow.core.api.DatabaseSchemaView.Column;
import com.bablsoft.accessflow.core.api.DatabaseSchemaView.Schema;
import com.bablsoft.accessflow.core.api.DatabaseSchemaView.Table;
import com.bablsoft.accessflow.core.api.DatasourceAdminService;
import com.bablsoft.accessflow.core.api.DbType;
import com.bablsoft.accessflow.core.api.QueryDetailView;
import com.bablsoft.accessflow.core.api.QueryDetailView.AiAnalysisDetail;
import com.bablsoft.accessflow.core.api.QueryRequestLookupService;
import com.bablsoft.accessflow.core.api.QueryRequestSnapshot;
import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.core.api.QueryType;
import com.bablsoft.accessflow.core.api.RiskLevel;
import com.bablsoft.accessflow.core.api.AiProviderType;
import com.bablsoft.accessflow.proxy.api.QueryParser;
import com.bablsoft.accessflow.core.api.SqlParseResult;
import com.bablsoft.accessflow.workflow.internal.persistence.entity.QuerySnapshotEntity;
import com.bablsoft.accessflow.workflow.internal.persistence.repo.QuerySnapshotRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Pageable;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultQuerySnapshotServiceTest {

    @Mock QuerySnapshotRepository repository;
    @Mock QueryRequestLookupService queryRequestLookupService;
    @Mock QueryParser queryParser;
    @Mock DatasourceAdminService datasourceAdminService;

    private DefaultQuerySnapshotService service;

    private final UUID queryId = UUID.randomUUID();
    private final UUID orgId = UUID.randomUUID();
    private final UUID dsId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final Instant executedAt = Instant.parse("2026-06-18T12:00:00Z");

    @BeforeEach
    void setUp() {
        service = new DefaultQuerySnapshotService(repository, queryRequestLookupService, queryParser,
                datasourceAdminService, new SchemaHasher(), new ObjectMapper());
    }

    private QueryRequestSnapshot snapshot() {
        return new QueryRequestSnapshot(queryId, dsId, orgId, userId, "SELECT * FROM users",
                QueryType.SELECT, false, QueryStatus.EXECUTED, null, "1.2.3.4", "curl", false);
    }

    private QueryDetailView detail(AiAnalysisDetail ai) {
        return new QueryDetailView(queryId, dsId, "prod", DbType.POSTGRESQL, orgId, userId,
                "a@x.com", "Alice", "SELECT * FROM users", QueryType.SELECT, QueryStatus.EXECUTED,
                "ticket-1", ai, 12L, 34, null, null, null, "Plan", 24, List.of(), null,
                Instant.parse("2026-06-18T11:00:00Z"), executedAt);
    }

    private AiAnalysisDetail ai() {
        return new AiAnalysisDetail(UUID.randomUUID(), RiskLevel.LOW, 10, "ok", "[]", "[]",
                false, 0L, AiProviderType.OPENAI, "gpt-4o", 1, 2, false, null);
    }

    private DatabaseSchemaView schema() {
        return new DatabaseSchemaView(List.of(new Schema("public", List.of(
                new Table("users", List.of(new Column("id", "int4", false, true)), List.of())))));
    }

    private void stubFound(QueryDetailView detail) {
        when(repository.existsByQueryRequestId(queryId)).thenReturn(false);
        when(queryRequestLookupService.findById(queryId)).thenReturn(Optional.of(snapshot()));
        when(queryRequestLookupService.findDetailById(queryId, orgId))
                .thenReturn(Optional.of(detail));
    }

    @Test
    void recordsSnapshotWithAllFields() {
        stubFound(detail(ai()));
        when(queryParser.parse("SELECT * FROM users", DbType.POSTGRESQL))
                .thenReturn(new SqlParseResult(QueryType.SELECT, false, List.of("SELECT * FROM users"),
                        Set.of("public.users")));
        when(datasourceAdminService.introspectSchemaForSystem(dsId, orgId)).thenReturn(schema());

        service.recordOnExecution(queryId);

        var captor = ArgumentCaptor.forClass(QuerySnapshotEntity.class);
        verify(repository).save(captor.capture());
        var saved = captor.getValue();
        assertThat(saved.getQueryRequestId()).isEqualTo(queryId);
        assertThat(saved.getOrganizationId()).isEqualTo(orgId);
        assertThat(saved.getDatasourceId()).isEqualTo(dsId);
        assertThat(saved.getSubmittedBy()).isEqualTo(userId);
        assertThat(saved.getSqlText()).isEqualTo("SELECT * FROM users");
        assertThat(saved.getDbType()).isEqualTo(DbType.POSTGRESQL);
        assertThat(saved.getReferencedTables()).containsExactly("public.users");
        assertThat(saved.getSchemaHash()).hasSize(64);
        assertThat(saved.getAiAnalysisJson()).contains("LOW");
        assertThat(saved.getReviewDecisionsJson()).isEqualTo("[]");
        assertThat(saved.getRowsAffected()).isEqualTo(12L);
        assertThat(saved.getExecutionDurationMs()).isEqualTo(34);
        assertThat(saved.getExecutedAt()).isEqualTo(executedAt);
    }

    @Test
    void skipsWhenSnapshotAlreadyExists() {
        when(repository.existsByQueryRequestId(queryId)).thenReturn(true);

        service.recordOnExecution(queryId);

        verify(repository, never()).save(any());
    }

    @Test
    void skipsWhenQueryNotFound() {
        when(repository.existsByQueryRequestId(queryId)).thenReturn(false);
        when(queryRequestLookupService.findById(queryId)).thenReturn(Optional.empty());

        service.recordOnExecution(queryId);

        verify(repository, never()).save(any());
    }

    @Test
    void skipsWhenDetailNotFound() {
        when(repository.existsByQueryRequestId(queryId)).thenReturn(false);
        when(queryRequestLookupService.findById(queryId)).thenReturn(Optional.of(snapshot()));
        when(queryRequestLookupService.findDetailById(queryId, orgId)).thenReturn(Optional.empty());

        service.recordOnExecution(queryId);

        verify(repository, never()).save(any());
    }

    @Test
    void parseFailureStoresNoReferencedTables() {
        stubFound(detail(ai()));
        when(queryParser.parse(any(), any())).thenThrow(new RuntimeException("boom"));
        when(datasourceAdminService.introspectSchemaForSystem(dsId, orgId)).thenReturn(schema());

        service.recordOnExecution(queryId);

        var captor = ArgumentCaptor.forClass(QuerySnapshotEntity.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getReferencedTables()).isEmpty();
    }

    @Test
    void introspectionFailureStoresNullSchemaHash() {
        stubFound(detail(ai()));
        when(queryParser.parse(any(), any()))
                .thenReturn(new SqlParseResult(QueryType.SELECT, "SELECT * FROM users"));
        when(datasourceAdminService.introspectSchemaForSystem(dsId, orgId))
                .thenThrow(new RuntimeException("db down"));

        service.recordOnExecution(queryId);

        var captor = ArgumentCaptor.forClass(QuerySnapshotEntity.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getSchemaHash()).isNull();
    }

    @Test
    void nullAiAnalysisStoresNullJson() {
        stubFound(detail(null));
        when(queryParser.parse(any(), any()))
                .thenReturn(new SqlParseResult(QueryType.SELECT, "SELECT * FROM users"));
        when(datasourceAdminService.introspectSchemaForSystem(dsId, orgId)).thenReturn(schema());

        service.recordOnExecution(queryId);

        var captor = ArgumentCaptor.forClass(QuerySnapshotEntity.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getAiAnalysisJson()).isNull();
    }

    @Test
    void uniqueRaceIsSwallowed() {
        stubFound(detail(ai()));
        when(queryParser.parse(any(), any()))
                .thenReturn(new SqlParseResult(QueryType.SELECT, "SELECT * FROM users"));
        when(datasourceAdminService.introspectSchemaForSystem(dsId, orgId)).thenReturn(schema());
        when(repository.save(any())).thenThrow(new DataIntegrityViolationException("dup"));

        assertThatCode(() -> service.recordOnExecution(queryId)).doesNotThrowAnyException();
    }

    private QuerySnapshotEntity entity(QueryType type) {
        var entity = new QuerySnapshotEntity();
        entity.setId(UUID.randomUUID());
        entity.setQueryRequestId(UUID.randomUUID());
        entity.setOrganizationId(orgId);
        entity.setDatasourceId(dsId);
        entity.setSubmittedBy(userId);
        entity.setSqlText("DELETE FROM users");
        entity.setQueryType(type);
        entity.setDbType(DbType.POSTGRESQL);
        entity.setExecutedAt(executedAt);
        return entity;
    }

    @Test
    void findForPeriodWithoutTypesUsesUnfilteredQuery() {
        var from = Instant.parse("2026-04-01T00:00:00Z");
        var to = Instant.parse("2026-07-01T00:00:00Z");
        when(repository.findForPeriod(eq(orgId), eq(from), eq(to), eq(dsId), any(Pageable.class)))
                .thenReturn(List.of(entity(QueryType.SELECT)));

        var result = service.findForPeriod(orgId, from, to, dsId, null, 100);

        assertThat(result).hasSize(1);
        verify(repository).findForPeriod(eq(orgId), eq(from), eq(to), eq(dsId), any(Pageable.class));
        verify(repository, never()).findForPeriodByType(any(), any(), any(), any(), any(), any());
    }

    @Test
    void findForPeriodWithEmptyTypesUsesUnfilteredQuery() {
        var from = Instant.parse("2026-04-01T00:00:00Z");
        var to = Instant.parse("2026-07-01T00:00:00Z");
        when(repository.findForPeriod(any(), any(), any(), any(), any(Pageable.class)))
                .thenReturn(List.of());

        service.findForPeriod(orgId, from, to, null, Set.of(), 100);

        verify(repository).findForPeriod(any(), any(), any(), any(), any(Pageable.class));
        verify(repository, never()).findForPeriodByType(any(), any(), any(), any(), any(), any());
    }

    @Test
    void findForPeriodWithTypesUsesFilteredQueryAndMaps() {
        var from = Instant.parse("2026-04-01T00:00:00Z");
        var to = Instant.parse("2026-07-01T00:00:00Z");
        var types = Set.of(QueryType.DDL, QueryType.DELETE);
        when(repository.findForPeriodByType(eq(orgId), eq(from), eq(to), eq(null), eq(types), any(Pageable.class)))
                .thenReturn(List.of(entity(QueryType.DELETE)));

        var result = service.findForPeriod(orgId, from, to, null, types, 100);

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().queryType()).isEqualTo(QueryType.DELETE);
        verify(repository, never()).findForPeriod(any(), any(), any(), any(), any(Pageable.class));
    }

    @Test
    void findForPeriodCapsPageSizeAtLeastOne() {
        when(repository.findForPeriod(any(), any(), any(), any(), any(Pageable.class)))
                .thenReturn(List.of());

        service.findForPeriod(orgId, executedAt, executedAt, null, null, 0);

        var captor = ArgumentCaptor.forClass(Pageable.class);
        verify(repository).findForPeriod(any(), any(), any(), any(), captor.capture());
        assertThat(captor.getValue().getPageSize()).isEqualTo(1);
    }

    @Test
    void findMapsEntityToView() {
        var entity = new QuerySnapshotEntity();
        entity.setId(UUID.randomUUID());
        entity.setQueryRequestId(queryId);
        entity.setOrganizationId(orgId);
        entity.setDatasourceId(dsId);
        entity.setSubmittedBy(userId);
        entity.setSqlText("SELECT 1");
        entity.setQueryType(QueryType.SELECT);
        entity.setDbType(DbType.POSTGRESQL);
        entity.setExecutedAt(executedAt);
        when(repository.findByQueryRequestIdAndOrganizationId(queryId, orgId))
                .thenReturn(Optional.of(entity));

        var view = service.find(queryId, orgId);

        assertThat(view).isPresent();
        assertThat(view.get().queryRequestId()).isEqualTo(queryId);
    }
}
