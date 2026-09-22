package com.bablsoft.accessflow.schemachange.internal.web;

import com.bablsoft.accessflow.core.api.QueryType;
import com.bablsoft.accessflow.deploygov.api.FreezeBehavior;
import com.bablsoft.accessflow.schemachange.api.SchemaChangeEnvironmentNoDatasourceException;
import com.bablsoft.accessflow.schemachange.api.SchemaChangeEnvironmentNotFoundException;
import com.bablsoft.accessflow.schemachange.api.SchemaChangePipelineNotFoundException;
import com.bablsoft.accessflow.schemachange.api.SchemaChangePromotionConflictException;
import com.bablsoft.accessflow.schemachange.api.SchemaChangePromotionDdlForbiddenException;
import com.bablsoft.accessflow.schemachange.api.SchemaChangePromotionFrozenException;
import com.bablsoft.accessflow.schemachange.api.SchemaChangePromotionLadderBlockedException;
import com.bablsoft.accessflow.schemachange.api.SchemaChangePromotionLadderInvalidException;
import com.bablsoft.accessflow.schemachange.api.SchemaChangePromotionNotCancellableException;
import com.bablsoft.accessflow.schemachange.api.SchemaChangePromotionNotFoundException;
import com.bablsoft.accessflow.schemachange.api.SchemaChangePromotionReviewUnenforceableException;
import com.bablsoft.accessflow.schemachange.api.SchemaChangePromotionStatus;
import com.bablsoft.accessflow.schemachange.api.SchemaChangeSetArchivedException;
import com.bablsoft.accessflow.schemachange.api.SchemaChangeSetEmptyException;
import com.bablsoft.accessflow.schemachange.api.SchemaChangeSetFrozenException;
import com.bablsoft.accessflow.schemachange.api.SchemaChangeSetNameConflictException;
import com.bablsoft.accessflow.schemachange.api.SchemaChangeSetNoTargetDatasourceException;
import com.bablsoft.accessflow.schemachange.api.SchemaChangeSetNotFoundException;
import com.bablsoft.accessflow.schemachange.api.SchemaChangeSetStatementBlockedException;
import com.bablsoft.accessflow.schemachange.api.SchemaChangeSetStatementInvalidException;
import com.bablsoft.accessflow.schemachange.api.SchemaChangeSetStatementLimitException;
import com.bablsoft.accessflow.schemachange.api.SchemaChangeSetStatus;
import com.bablsoft.accessflow.schemachange.api.SchemaChangeSetStatusTransitionException;
import com.bablsoft.accessflow.schemachange.api.SchemaChangeSetTargetDatasourceMissingException;
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
    void targetDatasourceMissingIs409WithBothIds() {
        var pipelineId = UUID.randomUUID();
        var datasourceId = UUID.randomUUID();

        var pd = handler.handleTargetDatasourceMissing(
                new SchemaChangeSetTargetDatasourceMissingException(pipelineId, datasourceId));

        assertProblem(pd, HttpStatus.CONFLICT, "SCHEMA_CHANGE_SET_TARGET_DATASOURCE_MISSING",
                "error.schema_change_set_target_datasource_missing");
        assertThat(pd.getProperties()).containsEntry("pipelineId", pipelineId).containsEntry("datasourceId", datasourceId);
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

    // ---- promotion (#880) ----

    @Test
    void promotionNotFoundIs404() {
        var pd = handler.handlePromotionNotFound(new SchemaChangePromotionNotFoundException(UUID.randomUUID()));

        assertProblem(pd, HttpStatus.NOT_FOUND, "SCHEMA_CHANGE_PROMOTION_NOT_FOUND",
                "error.schema_change_promotion_not_found");
    }

    @Test
    void environmentNotFoundIs404WithTheEnvironmentId() {
        var environmentId = UUID.randomUUID();

        var pd = handler.handleEnvironmentNotFound(new SchemaChangeEnvironmentNotFoundException(environmentId));

        assertProblem(pd, HttpStatus.NOT_FOUND, "SCHEMA_CHANGE_ENVIRONMENT_NOT_FOUND",
                "error.schema_change_environment_not_found");
        assertThat(pd.getProperties()).containsEntry("environmentId", environmentId);
    }

    @Test
    void environmentNoDatasourceIs422() {
        var environmentId = UUID.randomUUID();

        var pd = handler.handleEnvironmentNoDatasource(new SchemaChangeEnvironmentNoDatasourceException(environmentId));

        assertProblem(pd, HttpStatus.UNPROCESSABLE_CONTENT, "SCHEMA_CHANGE_ENVIRONMENT_NO_DATASOURCE",
                "error.schema_change_environment_no_datasource");
        assertThat(pd.getProperties()).containsEntry("environmentId", environmentId);
    }

    @Test
    void emptySetIs409() {
        var pd = handler.handleEmpty(new SchemaChangeSetEmptyException(UUID.randomUUID()));

        assertProblem(pd, HttpStatus.CONFLICT, "SCHEMA_CHANGE_SET_EMPTY", "error.schema_change_set_empty");
    }

    @Test
    void ladderInvalidIs409WithThePipelineId() {
        var pipelineId = UUID.randomUUID();

        var pd = handler.handleLadderInvalid(new SchemaChangePromotionLadderInvalidException(pipelineId));

        assertProblem(pd, HttpStatus.CONFLICT, "SCHEMA_CHANGE_PROMOTION_LADDER_INVALID",
                "error.schema_change_promotion_ladder_invalid");
        assertThat(pd.getProperties()).containsEntry("pipelineId", pipelineId);
    }

    @Test
    void ladderBlockedIs409NamingTheRung() {
        var environmentId = UUID.randomUUID();

        var pd = handler.handleLadderBlocked(
                new SchemaChangePromotionLadderBlockedException(UUID.randomUUID(), environmentId, "staging"));

        assertProblem(pd, HttpStatus.CONFLICT, "SCHEMA_CHANGE_PROMOTION_LADDER_BLOCKED",
                "error.schema_change_promotion_ladder_blocked[staging]");
        assertThat(pd.getProperties()).containsEntry("blockingEnvironmentId", environmentId)
                .containsEntry("blockingEnvironmentName", "staging");
    }

    @Test
    void frozenIs409WithWindowAndBehavior() {
        var environmentId = UUID.randomUUID();
        var windowId = UUID.randomUUID();

        var pd = handler.handleFrozenWindow(
                new SchemaChangePromotionFrozenException(environmentId, windowId, FreezeBehavior.REJECT, "release"));

        assertProblem(pd, HttpStatus.CONFLICT, "SCHEMA_CHANGE_PROMOTION_FROZEN",
                "error.schema_change_promotion_frozen[REJECT]");
        assertThat(pd.getProperties()).containsEntry("environmentId", environmentId)
                .containsEntry("freezeWindowId", windowId).containsEntry("behavior", "REJECT")
                .containsEntry("reason", "release");
    }

    @Test
    void frozenWithoutReasonOmitsTheProperty() {
        var pd = handler.handleFrozenWindow(
                new SchemaChangePromotionFrozenException(UUID.randomUUID(), UUID.randomUUID(), FreezeBehavior.HOLD, null));

        assertThat(pd.getProperties()).containsEntry("behavior", "HOLD").doesNotContainKey("reason");
    }

    @Test
    void reviewUnenforceableIs422() {
        var environmentId = UUID.randomUUID();
        var datasourceId = UUID.randomUUID();

        var pd = handler.handleReviewUnenforceable(
                new SchemaChangePromotionReviewUnenforceableException(environmentId, datasourceId));

        assertProblem(pd, HttpStatus.UNPROCESSABLE_CONTENT, "SCHEMA_CHANGE_PROMOTION_REVIEW_UNENFORCEABLE",
                "error.schema_change_promotion_review_unenforceable");
        assertThat(pd.getProperties()).containsEntry("environmentId", environmentId)
                .containsEntry("datasourceId", datasourceId);
    }

    @Test
    void ddlForbiddenIs403() {
        var datasourceId = UUID.randomUUID();

        var pd = handler.handleDdlForbidden(new SchemaChangePromotionDdlForbiddenException(datasourceId));

        assertProblem(pd, HttpStatus.FORBIDDEN, "SCHEMA_CHANGE_PROMOTION_DDL_FORBIDDEN",
                "error.schema_change_promotion_ddl_forbidden");
        assertThat(pd.getProperties()).containsEntry("datasourceId", datasourceId);
    }

    @Test
    void promotionConflictIs409() {
        var changeSetId = UUID.randomUUID();
        var environmentId = UUID.randomUUID();

        var pd = handler.handlePromotionConflict(new SchemaChangePromotionConflictException(changeSetId, environmentId));

        assertProblem(pd, HttpStatus.CONFLICT, "SCHEMA_CHANGE_PROMOTION_CONFLICT",
                "error.schema_change_promotion_conflict");
        assertThat(pd.getProperties()).containsEntry("changeSetId", changeSetId)
                .containsEntry("environmentId", environmentId);
    }

    @Test
    void notCancellableIs409WithTheStatus() {
        var pd = handler.handleNotCancellable(
                new SchemaChangePromotionNotCancellableException(UUID.randomUUID(), SchemaChangePromotionStatus.APPLIED));

        assertProblem(pd, HttpStatus.CONFLICT, "SCHEMA_CHANGE_PROMOTION_NOT_CANCELLABLE",
                "error.schema_change_promotion_not_cancellable[APPLIED]");
        assertThat(pd.getProperties()).containsEntry("currentStatus", "APPLIED");
    }

    private static void assertProblem(ProblemDetail pd, HttpStatus status, String error, String detail) {
        assertThat(pd.getStatus()).isEqualTo(status.value());
        assertThat(pd.getDetail()).isEqualTo(detail);
        assertThat(pd.getProperties()).containsEntry("error", error).containsKey("timestamp");
    }
}
