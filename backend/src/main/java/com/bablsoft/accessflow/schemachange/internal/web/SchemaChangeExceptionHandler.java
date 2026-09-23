package com.bablsoft.accessflow.schemachange.internal.web;

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
import com.bablsoft.accessflow.schemachange.api.SchemaChangeSetArchivedException;
import com.bablsoft.accessflow.schemachange.api.SchemaChangeSetEmptyException;
import com.bablsoft.accessflow.schemachange.api.SchemaChangeSetFrozenException;
import com.bablsoft.accessflow.schemachange.api.SchemaChangeSetNameConflictException;
import com.bablsoft.accessflow.schemachange.api.SchemaChangeSetNoTargetDatasourceException;
import com.bablsoft.accessflow.schemachange.api.SchemaChangeSetNotFoundException;
import com.bablsoft.accessflow.schemachange.api.SchemaChangeSetStatementBlockedException;
import com.bablsoft.accessflow.schemachange.api.SchemaChangeSetStatementInvalidException;
import com.bablsoft.accessflow.schemachange.api.SchemaChangeSetStatementLimitException;
import com.bablsoft.accessflow.schemachange.api.SchemaChangeSetStatusTransitionException;
import com.bablsoft.accessflow.schemachange.api.SchemaChangeSetTargetDatasourceMissingException;
import com.bablsoft.accessflow.schemachange.api.SchemaDriftBaselineEnvironmentInvalidException;
import com.bablsoft.accessflow.schemachange.api.SchemaDriftConcurrentUpdateException;
import com.bablsoft.accessflow.schemachange.api.SchemaDriftFindingNotAcknowledgeableException;
import com.bablsoft.accessflow.schemachange.api.SchemaDriftFindingNotFoundException;
import com.bablsoft.accessflow.schemachange.api.SchemaDriftScanInProgressException;
import com.bablsoft.accessflow.sqlreview.api.SqlReviewFindingRenderer;
import lombok.RequiredArgsConstructor;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;

// Higher precedence than the security module's GlobalExceptionHandler catch-all.
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
@RequiredArgsConstructor
class SchemaChangeExceptionHandler {

    private final MessageSource messageSource;
    private final SqlReviewFindingRenderer findingRenderer;

    private String msg(String key, Object... args) {
        return messageSource.getMessage(key, args, LocaleContextHolder.getLocale());
    }

    @ExceptionHandler(SchemaChangePipelineNotFoundException.class)
    ProblemDetail handlePipelineNotFound(SchemaChangePipelineNotFoundException ex) {
        var pd = problem(HttpStatus.NOT_FOUND, msg("error.schema_change_pipeline_not_found"),
                "SCHEMA_CHANGE_PIPELINE_NOT_FOUND");
        pd.setProperty("pipelineId", ex.pipelineId());
        return pd;
    }

    @ExceptionHandler(SchemaChangeSetNotFoundException.class)
    ProblemDetail handleNotFound(SchemaChangeSetNotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, msg("error.schema_change_set_not_found"), "SCHEMA_CHANGE_SET_NOT_FOUND");
    }

    @ExceptionHandler(SchemaChangeSetNameConflictException.class)
    ProblemDetail handleNameConflict(SchemaChangeSetNameConflictException ex) {
        var pd = problem(HttpStatus.CONFLICT, msg("error.schema_change_set_name_conflict", ex.name()),
                "SCHEMA_CHANGE_SET_NAME_CONFLICT");
        pd.setProperty("pipelineId", ex.pipelineId());
        pd.setProperty("name", ex.name());
        return pd;
    }

    @ExceptionHandler(SchemaChangeSetNoTargetDatasourceException.class)
    ProblemDetail handleNoTargetDatasource(SchemaChangeSetNoTargetDatasourceException ex) {
        var pd = problem(HttpStatus.CONFLICT, msg("error.schema_change_set_no_target_datasource"),
                "SCHEMA_CHANGE_SET_NO_TARGET_DATASOURCE");
        pd.setProperty("pipelineId", ex.pipelineId());
        return pd;
    }

    @ExceptionHandler(SchemaChangeSetTargetDatasourceMissingException.class)
    ProblemDetail handleTargetDatasourceMissing(SchemaChangeSetTargetDatasourceMissingException ex) {
        var pd = problem(HttpStatus.CONFLICT, msg("error.schema_change_set_target_datasource_missing"),
                "SCHEMA_CHANGE_SET_TARGET_DATASOURCE_MISSING");
        pd.setProperty("pipelineId", ex.pipelineId());
        pd.setProperty("datasourceId", ex.datasourceId());
        return pd;
    }

    @ExceptionHandler(SchemaChangeSetStatementLimitException.class)
    ProblemDetail handleStatementLimit(SchemaChangeSetStatementLimitException ex) {
        var pd = problem(HttpStatus.BAD_REQUEST,
                msg("error.schema_change_set_statement_limit", ex.limit(), ex.actual()),
                "SCHEMA_CHANGE_SET_STATEMENT_LIMIT");
        pd.setProperty("limit", ex.limit());
        pd.setProperty("actual", ex.actual());
        return pd;
    }

    @ExceptionHandler(SchemaChangeSetStatementInvalidException.class)
    ProblemDetail handleStatementInvalid(SchemaChangeSetStatementInvalidException ex) {
        var number = ex.statementIndex() + 1;
        var pd = switch (ex.reason()) {
            case UNPARSEABLE -> problem(HttpStatus.UNPROCESSABLE_CONTENT,
                    msg("error.schema_change_set_statement_invalid", number,
                            ex.parserMessage() == null ? "" : ex.parserMessage()),
                    "SCHEMA_CHANGE_SET_STATEMENT_INVALID");
            case DML -> problem(HttpStatus.UNPROCESSABLE_CONTENT,
                    msg("error.schema_change_set_statement_dml", number, ex.queryType()),
                    "SCHEMA_CHANGE_SET_STATEMENT_DML");
            case TRANSACTION_ENVELOPE -> problem(HttpStatus.UNPROCESSABLE_CONTENT,
                    msg("error.schema_change_set_statement_transaction_envelope", number),
                    "SCHEMA_CHANGE_SET_STATEMENT_TRANSACTION_ENVELOPE");
            case MULTIPLE_STATEMENTS -> problem(HttpStatus.UNPROCESSABLE_CONTENT,
                    msg("error.schema_change_set_statement_multiple", number),
                    "SCHEMA_CHANGE_SET_STATEMENT_MULTIPLE");
        };
        pd.setProperty("statementIndex", ex.statementIndex());
        if (ex.queryType() != null) {
            pd.setProperty("queryType", ex.queryType().name());
        }
        return pd;
    }

    @ExceptionHandler(SchemaChangeSetStatementBlockedException.class)
    ProblemDetail handleStatementBlocked(SchemaChangeSetStatementBlockedException ex) {
        var locale = LocaleContextHolder.getLocale();
        var pd = problem(HttpStatus.UNPROCESSABLE_CONTENT, msg("error.schema_change_set_statement_blocked"),
                "SCHEMA_CHANGE_SET_STATEMENT_BLOCKED");
        pd.setProperty("findings", ex.findings().stream()
                .map(f -> SchemaChangeStatementFindingResponse.from(f, findingRenderer.message(f.finding(), locale)))
                .toList());
        return pd;
    }

    @ExceptionHandler(SchemaChangeSetFrozenException.class)
    ProblemDetail handleFrozen(SchemaChangeSetFrozenException ex) {
        return problem(HttpStatus.CONFLICT, msg("error.schema_change_set_frozen"), "SCHEMA_CHANGE_SET_FROZEN");
    }

    @ExceptionHandler(SchemaChangeSetArchivedException.class)
    ProblemDetail handleArchived(SchemaChangeSetArchivedException ex) {
        return problem(HttpStatus.CONFLICT, msg("error.schema_change_set_archived"), "SCHEMA_CHANGE_SET_ARCHIVED");
    }

    @ExceptionHandler(SchemaChangeSetStatusTransitionException.class)
    ProblemDetail handleStatusTransition(SchemaChangeSetStatusTransitionException ex) {
        var pd = problem(HttpStatus.CONFLICT,
                msg("error.schema_change_set_invalid_status_transition", ex.currentStatus(), ex.requestedStatus()),
                "SCHEMA_CHANGE_SET_INVALID_STATUS_TRANSITION");
        pd.setProperty("currentStatus", ex.currentStatus().name());
        pd.setProperty("requestedStatus", ex.requestedStatus().name());
        return pd;
    }

    // ---- promotion (#880) ----

    @ExceptionHandler(SchemaChangePromotionNotFoundException.class)
    ProblemDetail handlePromotionNotFound(SchemaChangePromotionNotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, msg("error.schema_change_promotion_not_found"),
                "SCHEMA_CHANGE_PROMOTION_NOT_FOUND");
    }

    @ExceptionHandler(SchemaChangeEnvironmentNotFoundException.class)
    ProblemDetail handleEnvironmentNotFound(SchemaChangeEnvironmentNotFoundException ex) {
        var pd = problem(HttpStatus.NOT_FOUND, msg("error.schema_change_environment_not_found"),
                "SCHEMA_CHANGE_ENVIRONMENT_NOT_FOUND");
        pd.setProperty("environmentId", ex.environmentId());
        return pd;
    }

    @ExceptionHandler(SchemaChangeEnvironmentNoDatasourceException.class)
    ProblemDetail handleEnvironmentNoDatasource(SchemaChangeEnvironmentNoDatasourceException ex) {
        var pd = problem(HttpStatus.UNPROCESSABLE_CONTENT, msg("error.schema_change_environment_no_datasource"),
                "SCHEMA_CHANGE_ENVIRONMENT_NO_DATASOURCE");
        pd.setProperty("environmentId", ex.environmentId());
        return pd;
    }

    @ExceptionHandler(SchemaChangeSetEmptyException.class)
    ProblemDetail handleEmpty(SchemaChangeSetEmptyException ex) {
        return problem(HttpStatus.CONFLICT, msg("error.schema_change_set_empty"), "SCHEMA_CHANGE_SET_EMPTY");
    }

    @ExceptionHandler(SchemaChangePromotionLadderInvalidException.class)
    ProblemDetail handleLadderInvalid(SchemaChangePromotionLadderInvalidException ex) {
        var pd = problem(HttpStatus.CONFLICT, msg("error.schema_change_promotion_ladder_invalid"),
                "SCHEMA_CHANGE_PROMOTION_LADDER_INVALID");
        pd.setProperty("pipelineId", ex.pipelineId());
        return pd;
    }

    @ExceptionHandler(SchemaChangePromotionLadderBlockedException.class)
    ProblemDetail handleLadderBlocked(SchemaChangePromotionLadderBlockedException ex) {
        var pd = problem(HttpStatus.CONFLICT,
                msg("error.schema_change_promotion_ladder_blocked", ex.blockingEnvironmentName()),
                "SCHEMA_CHANGE_PROMOTION_LADDER_BLOCKED");
        pd.setProperty("blockingEnvironmentId", ex.blockingEnvironmentId());
        pd.setProperty("blockingEnvironmentName", ex.blockingEnvironmentName());
        return pd;
    }

    @ExceptionHandler(SchemaChangePromotionFrozenException.class)
    ProblemDetail handleFrozenWindow(SchemaChangePromotionFrozenException ex) {
        var pd = problem(HttpStatus.CONFLICT, msg("error.schema_change_promotion_frozen", ex.behavior()),
                "SCHEMA_CHANGE_PROMOTION_FROZEN");
        pd.setProperty("environmentId", ex.environmentId());
        pd.setProperty("freezeWindowId", ex.freezeWindowId());
        pd.setProperty("behavior", ex.behavior().name());
        if (ex.reason() != null) {
            pd.setProperty("reason", ex.reason());
        }
        return pd;
    }

    @ExceptionHandler(SchemaChangePromotionReviewUnenforceableException.class)
    ProblemDetail handleReviewUnenforceable(SchemaChangePromotionReviewUnenforceableException ex) {
        var pd = problem(HttpStatus.UNPROCESSABLE_CONTENT, msg("error.schema_change_promotion_review_unenforceable"),
                "SCHEMA_CHANGE_PROMOTION_REVIEW_UNENFORCEABLE");
        pd.setProperty("environmentId", ex.environmentId());
        pd.setProperty("datasourceId", ex.datasourceId());
        return pd;
    }

    @ExceptionHandler(SchemaChangePromotionDdlForbiddenException.class)
    ProblemDetail handleDdlForbidden(SchemaChangePromotionDdlForbiddenException ex) {
        var pd = problem(HttpStatus.FORBIDDEN, msg("error.schema_change_promotion_ddl_forbidden"),
                "SCHEMA_CHANGE_PROMOTION_DDL_FORBIDDEN");
        pd.setProperty("datasourceId", ex.datasourceId());
        return pd;
    }

    @ExceptionHandler(SchemaChangePromotionConflictException.class)
    ProblemDetail handlePromotionConflict(SchemaChangePromotionConflictException ex) {
        var pd = problem(HttpStatus.CONFLICT, msg("error.schema_change_promotion_conflict"),
                "SCHEMA_CHANGE_PROMOTION_CONFLICT");
        pd.setProperty("changeSetId", ex.changeSetId());
        pd.setProperty("environmentId", ex.environmentId());
        return pd;
    }

    @ExceptionHandler(SchemaChangePromotionNotCancellableException.class)
    ProblemDetail handleNotCancellable(SchemaChangePromotionNotCancellableException ex) {
        var pd = problem(HttpStatus.CONFLICT,
                msg("error.schema_change_promotion_not_cancellable", ex.currentStatus()),
                "SCHEMA_CHANGE_PROMOTION_NOT_CANCELLABLE");
        pd.setProperty("currentStatus", ex.currentStatus().name());
        return pd;
    }

    @ExceptionHandler(SchemaDriftFindingNotFoundException.class)
    ProblemDetail handleDriftFindingNotFound(SchemaDriftFindingNotFoundException ex) {
        var pd = problem(HttpStatus.NOT_FOUND, msg("error.schema_drift_finding_not_found"),
                "SCHEMA_DRIFT_FINDING_NOT_FOUND");
        pd.setProperty("findingId", ex.findingId());
        return pd;
    }

    @ExceptionHandler(SchemaDriftScanInProgressException.class)
    ProblemDetail handleDriftScanInProgress(SchemaDriftScanInProgressException ex) {
        var pd = problem(HttpStatus.CONFLICT, msg("error.schema_drift_scan_in_progress"),
                "SCHEMA_DRIFT_SCAN_IN_PROGRESS");
        pd.setProperty("environmentId", ex.environmentId());
        return pd;
    }

    @ExceptionHandler(SchemaDriftFindingNotAcknowledgeableException.class)
    ProblemDetail handleDriftFindingNotAcknowledgeable(SchemaDriftFindingNotAcknowledgeableException ex) {
        var pd = problem(HttpStatus.CONFLICT,
                msg("error.schema_drift_finding_not_acknowledgeable", ex.currentStatus()),
                "SCHEMA_DRIFT_FINDING_NOT_ACKNOWLEDGEABLE");
        pd.setProperty("findingId", ex.findingId());
        pd.setProperty("currentStatus", ex.currentStatus().name());
        return pd;
    }

    @ExceptionHandler(SchemaDriftBaselineEnvironmentInvalidException.class)
    ProblemDetail handleDriftBaselineInvalid(SchemaDriftBaselineEnvironmentInvalidException ex) {
        var pd = problem(HttpStatus.UNPROCESSABLE_CONTENT,
                msg("error.schema_drift_baseline_environment_invalid"),
                "SCHEMA_DRIFT_BASELINE_ENVIRONMENT_INVALID");
        pd.setProperty("pipelineId", ex.pipelineId());
        if (ex.baselineEnvironmentId() != null) {
            pd.setProperty("baselineEnvironmentId", ex.baselineEnvironmentId());
        }
        return pd;
    }

    @ExceptionHandler(SchemaDriftConcurrentUpdateException.class)
    ProblemDetail handleDriftConcurrentUpdate(SchemaDriftConcurrentUpdateException ex) {
        var pd = problem(HttpStatus.CONFLICT, msg("error.schema_drift_concurrent_update"),
                "SCHEMA_DRIFT_CONCURRENT_UPDATE");
        pd.setProperty("resourceId", ex.resourceId());
        return pd;
    }

    private static ProblemDetail problem(HttpStatus status, String detail, String error) {
        var pd = ProblemDetail.forStatusAndDetail(status, detail);
        pd.setProperty("error", error);
        pd.setProperty("timestamp", Instant.now().toString());
        return pd;
    }
}
