package com.bablsoft.accessflow.sqlreview.internal.web;

import com.bablsoft.accessflow.core.api.Permission;
import com.bablsoft.accessflow.security.api.JwtClaims;
import com.bablsoft.accessflow.sqlreview.api.SqlReviewFindingRenderer;
import com.bablsoft.accessflow.sqlreview.api.SqlReviewRuleCatalogService;
import com.bablsoft.accessflow.sqlreview.api.SqlReviewService;
import com.bablsoft.accessflow.sqlreview.internal.web.model.EvaluateSqlReviewRequest;
import com.bablsoft.accessflow.sqlreview.internal.web.model.SqlReviewEvaluationResponse;
import com.bablsoft.accessflow.sqlreview.internal.web.model.SqlReviewRuleResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/**
 * The rule catalog and the editor's live-lint endpoint (#863). Evaluation is read-only — nothing is
 * persisted, audited or published — and is authorized by the caller's visibility of the datasource,
 * exactly as {@code POST /queries/analyze} and {@code POST /queries/dry-run} are.
 */
@RestController
@RequestMapping("/api/v1/sql-review")
@Tag(name = "SQL Review", description = "Deterministic SQL review rule catalog and read-only evaluation")
@RequiredArgsConstructor
class SqlReviewController {

    private final SqlReviewService sqlReviewService;
    private final SqlReviewRuleCatalogService catalogService;
    private final SqlReviewFindingRenderer findingRenderer;
    private final MessageSource messageSource;

    @GetMapping("/rules")
    @PreAuthorize("hasAuthority('PERM_SQL_REVIEW_MANAGE')")
    @Operation(summary = "The built-in SQL review rule catalog, localized in the request locale")
    @ApiResponse(responseCode = "200", description = "Rules in catalog order")
    @ApiResponse(responseCode = "403", description = "Caller lacks SQL_REVIEW_MANAGE")
    List<SqlReviewRuleResponse> rules() {
        return catalogService.rules(LocaleContextHolder.getLocale()).stream()
                .map(SqlReviewRuleResponse::from)
                .toList();
    }

    @PostMapping("/evaluate")
    @Operation(summary = "Evaluate SQL against the datasource's resolved SQL review ruleset",
            description = "Read-only editor lint: no finding is persisted, no audit row is written and "
                    + "nothing runs against the customer database. A datasource the caller cannot see is "
                    + "a 404, never a 403. Engine-plugin datasources return applicable=false.")
    @ApiResponse(responseCode = "200", description = "Findings with localized messages")
    @ApiResponse(responseCode = "400", description = "Validation error or unreadable body")
    @ApiResponse(responseCode = "404", description = "Datasource not found or not accessible")
    @ApiResponse(responseCode = "422", description = "SQL could not be parsed")
    SqlReviewEvaluationResponse evaluate(@Valid @RequestBody EvaluateSqlReviewRequest body,
                                         Authentication authentication) {
        var caller = (JwtClaims) authentication.getPrincipal();
        var locale = LocaleContextHolder.getLocale();
        var result = sqlReviewService.evaluateForUser(caller.organizationId(), caller.userId(),
                caller.has(Permission.QUERY_ADMIN), body.datasourceId(), body.sql());
        return SqlReviewEvaluationResponse.from(result, finding -> findingRenderer.message(finding, locale));
    }

    /**
     * A body that will not deserialize — malformed JSON or a non-UUID {@code datasource_id} — is a
     * client error; nothing maps the parse failure globally, and the editor calls this on a debounce
     * so a 500 per keystroke would be both wrong and noisy.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    ProblemDetail handleUnreadableBody(HttpMessageNotReadableException ex) {
        var detail = messageSource.getMessage("error.sql_review_evaluate_body_unreadable", null,
                LocaleContextHolder.getLocale());
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail);
        problem.setProperty("error", "VALIDATION_ERROR");
        problem.setProperty("timestamp", Instant.now().toString());
        return problem;
    }
}
