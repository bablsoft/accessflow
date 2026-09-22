package com.bablsoft.accessflow.schemachange.internal.web;

import com.bablsoft.accessflow.core.api.QueryType;
import com.bablsoft.accessflow.schemachange.api.SchemaChangePipelineNotFoundException;
import com.bablsoft.accessflow.schemachange.api.SchemaChangeSetArchivedException;
import com.bablsoft.accessflow.schemachange.api.SchemaChangeSetFrozenException;
import com.bablsoft.accessflow.schemachange.api.SchemaChangeSetNameConflictException;
import com.bablsoft.accessflow.schemachange.api.SchemaChangeSetNoTargetDatasourceException;
import com.bablsoft.accessflow.schemachange.api.SchemaChangeSetNotFoundException;
import com.bablsoft.accessflow.schemachange.api.SchemaChangeSetStatementBlockedException;
import com.bablsoft.accessflow.schemachange.api.SchemaChangeSetStatementInvalidException;
import com.bablsoft.accessflow.schemachange.api.SchemaChangeSetStatementLimitException;
import com.bablsoft.accessflow.schemachange.api.SchemaChangeSetStatus;
import com.bablsoft.accessflow.schemachange.api.SchemaChangeSetStatusTransitionException;
import com.bablsoft.accessflow.schemachange.api.SchemaChangeStatementFinding;
import com.bablsoft.accessflow.sqlreview.api.SqlReviewFinding;
import com.bablsoft.accessflow.sqlreview.api.SqlReviewFindingRenderer;
import com.bablsoft.accessflow.sqlreview.api.SqlReviewSeverity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.MessageSource;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SchemaChangeExceptionHandlerTest {

    private SchemaChangeExceptionHandler handler;

    @BeforeEach
    void setUp() {
        var messageSource = mock(MessageSource.class);
        when(messageSource.getMessage(any(String.class), any(), any())).thenAnswer(inv -> {
            Object[] args = inv.getArgument(1);
            return inv.getArgument(0) + (args == null || args.length == 0 ? "" : Arrays.toString(args));
        });
        var renderer = mock(SqlReviewFindingRenderer.class);
        when(renderer.message(any(SqlReviewFinding.class), any(Locale.class)))
                .thenAnswer(inv -> "rendered:" + ((SqlReviewFinding) inv.getArgument(0)).ruleId());
        handler = new SchemaChangeExceptionHandler(messageSource, renderer);
    }

    @Test
    void handlerOutranksTheSecurityCatchAll() {
        var order = SchemaChangeExceptionHandler.class.getAnnotation(Order.class);

        assertThat(order).isNotNull();
        assertThat(order.value()).isEqualTo(Ordered.HIGHEST_PRECEDENCE);
    }

    @Test
    void pipelineNotFoundIs404() {
        var pipelineId = UUID.randomUUID();

        var pd = handler.handlePipelineNotFound(new SchemaChangePipelineNotFoundException(pipelineId));

        assertProblem(pd, HttpStatus.NOT_FOUND, "SCHEMA_CHANGE_PIPELINE_NOT_FOUND", "error.schema_change_pipeline_not_found");
        assertThat(pd.getProperties()).containsEntry("pipelineId", pipelineId);
    }

    @Test
    void changeSetNotFoundIs404() {
        var pd = handler.handleNotFound(new SchemaChangeSetNotFoundException(UUID.randomUUID()));

        assertProblem(pd, HttpStatus.NOT_FOUND, "SCHEMA_CHANGE_SET_NOT_FOUND", "error.schema_change_set_not_found");
    }

    @Test
    void nameConflictIs409WithTheName() {
        var pipelineId = UUID.randomUUID();

        var pd = handler.handleNameConflict(new SchemaChangeSetNameConflictException(pipelineId, "cs"));

        assertProblem(pd, HttpStatus.CONFLICT, "SCHEMA_CHANGE_SET_NAME_CONFLICT", "error.schema_change_set_name_conflict[cs]");
        assertThat(pd.getProperties()).containsEntry("pipelineId", pipelineId).containsEntry("name", "cs");
    }

    @Test
    void noTargetDatasourceIs409() {
        var pipelineId = UUID.randomUUID();

        var pd = handler.handleNoTargetDatasource(new SchemaChangeSetNoTargetDatasourceException(pipelineId));

        assertProblem(pd, HttpStatus.CONFLICT, "SCHEMA_CHANGE_SET_NO_TARGET_DATASOURCE",
                "error.schema_change_set_no_target_datasource");
        assertThat(pd.getProperties()).containsEntry("pipelineId", pipelineId);
    }

    @Test
    void statementLimitIs400WithLimitAndActual() {
        var pd = handler.handleStatementLimit(new SchemaChangeSetStatementLimitException(50, 51));

        assertProblem(pd, HttpStatus.BAD_REQUEST, "SCHEMA_CHANGE_SET_STATEMENT_LIMIT",
                "error.schema_change_set_statement_limit[50, 51]");
        assertThat(pd.getProperties()).containsEntry("limit", 50).containsEntry("actual", 51);
    }

    @Test
    void unparseableStatementIs422WithTheParserDetail() {
        var pd = handler.handleStatementInvalid(SchemaChangeSetStatementInvalidException.unparseable(2, "bad token"));

        assertProblem(pd, HttpStatus.UNPROCESSABLE_CONTENT, "SCHEMA_CHANGE_SET_STATEMENT_INVALID",
                "error.schema_change_set_statement_invalid[3, bad token]");
        assertThat(pd.getProperties()).containsEntry("statementIndex", 2).doesNotContainKey("queryType");
    }

    @Test
    void unparseableStatementWithoutDetailRendersAnEmptyReason() {
        var pd = handler.handleStatementInvalid(SchemaChangeSetStatementInvalidException.unparseable(0, null));

        assertThat(pd.getDetail()).isEqualTo("error.schema_change_set_statement_invalid[1, ]");
    }

    @Test
    void dmlStatementIs422WithTheQueryType() {
        var pd = handler.handleStatementInvalid(SchemaChangeSetStatementInvalidException.dml(0, QueryType.DELETE));

        assertProblem(pd, HttpStatus.UNPROCESSABLE_CONTENT, "SCHEMA_CHANGE_SET_STATEMENT_DML",
                "error.schema_change_set_statement_dml[1, DELETE]");
        assertThat(pd.getProperties()).containsEntry("statementIndex", 0).containsEntry("queryType", "DELETE");
    }

    @Test
    void transactionEnvelopeAndMultipleStatementsHaveTheirOwnCodes() {
        var envelope = handler.handleStatementInvalid(SchemaChangeSetStatementInvalidException.transactionEnvelope(4));
        var multiple = handler.handleStatementInvalid(SchemaChangeSetStatementInvalidException.multipleStatements(1));

        assertProblem(envelope, HttpStatus.UNPROCESSABLE_CONTENT, "SCHEMA_CHANGE_SET_STATEMENT_TRANSACTION_ENVELOPE",
                "error.schema_change_set_statement_transaction_envelope[5]");
        assertThat(envelope.getProperties()).containsEntry("statementIndex", 4);
        assertProblem(multiple, HttpStatus.UNPROCESSABLE_CONTENT, "SCHEMA_CHANGE_SET_STATEMENT_MULTIPLE",
                "error.schema_change_set_statement_multiple[2]");
        assertThat(multiple.getProperties()).containsEntry("statementIndex", 1);
    }

    @Test
    @SuppressWarnings("unchecked")
    void blockedStatementIs422WithRenderedFindings() {
        var datasourceId = UUID.randomUUID();
        var finding = new SchemaChangeStatementFinding(1, datasourceId,
                new SqlReviewFinding("drop_statement", SqlReviewSeverity.BLOCK, 0, 3, Map.of("table", "t")));

        var pd = handler.handleStatementBlocked(new SchemaChangeSetStatementBlockedException(List.of(finding)));

        assertProblem(pd, HttpStatus.UNPROCESSABLE_CONTENT, "SCHEMA_CHANGE_SET_STATEMENT_BLOCKED",
                "error.schema_change_set_statement_blocked");
        var findings = (List<SchemaChangeStatementFindingResponse>) pd.getProperties().get("findings");
        assertThat(findings).singleElement().satisfies(f -> {
            assertThat(f.statementIndex()).isEqualTo(1);
            assertThat(f.datasourceId()).isEqualTo(datasourceId);
            assertThat(f.ruleId()).isEqualTo("drop_statement");
            assertThat(f.severity()).isEqualTo(SqlReviewSeverity.BLOCK);
            assertThat(f.lineNumber()).isEqualTo(3);
            assertThat(f.message()).isEqualTo("rendered:drop_statement");
        });
    }

    @Test
    void frozenAndArchivedAre409() {
        var frozen = handler.handleFrozen(new SchemaChangeSetFrozenException(UUID.randomUUID()));
        var archived = handler.handleArchived(new SchemaChangeSetArchivedException(UUID.randomUUID()));

        assertProblem(frozen, HttpStatus.CONFLICT, "SCHEMA_CHANGE_SET_FROZEN", "error.schema_change_set_frozen");
        assertProblem(archived, HttpStatus.CONFLICT, "SCHEMA_CHANGE_SET_ARCHIVED", "error.schema_change_set_archived");
    }

    @Test
    void statusTransitionIs409WithBothStatuses() {
        var pd = handler.handleStatusTransition(new SchemaChangeSetStatusTransitionException(UUID.randomUUID(),
                SchemaChangeSetStatus.DRAFT, SchemaChangeSetStatus.ACTIVE));

        assertProblem(pd, HttpStatus.CONFLICT, "SCHEMA_CHANGE_SET_INVALID_STATUS_TRANSITION",
                "error.schema_change_set_invalid_status_transition[DRAFT, ACTIVE]");
        assertThat(pd.getProperties()).containsEntry("currentStatus", "DRAFT").containsEntry("requestedStatus", "ACTIVE");
    }

    private static void assertProblem(ProblemDetail pd, HttpStatus status, String error, String detail) {
        assertThat(pd.getStatus()).isEqualTo(status.value());
        assertThat(pd.getDetail()).isEqualTo(detail);
        assertThat(pd.getProperties()).containsEntry("error", error).containsKey("timestamp");
    }
}
