package com.bablsoft.accessflow.sqlreview.internal.web;

import com.bablsoft.accessflow.audit.api.AuditAction;
import com.bablsoft.accessflow.audit.api.AuditEntry;
import com.bablsoft.accessflow.audit.api.AuditLogService;
import com.bablsoft.accessflow.audit.api.AuditResourceType;
import com.bablsoft.accessflow.audit.api.RequestAuditContext;
import com.bablsoft.accessflow.core.api.DbType;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.security.api.JwtClaims;
import com.bablsoft.accessflow.sqlreview.api.IllegalSqlReviewCustomRuleException;
import com.bablsoft.accessflow.sqlreview.api.SqlReviewCustomRuleCommand;
import com.bablsoft.accessflow.sqlreview.api.SqlReviewCustomRuleService;
import com.bablsoft.accessflow.sqlreview.api.SqlReviewCustomRuleView;
import com.bablsoft.accessflow.sqlreview.api.SqlReviewFinding;
import com.bablsoft.accessflow.sqlreview.api.SqlReviewFindingRenderer;
import com.bablsoft.accessflow.sqlreview.api.SqlReviewResult;
import com.bablsoft.accessflow.sqlreview.api.SqlReviewSeverity;
import com.bablsoft.accessflow.sqlreview.api.SqlRuleCategory;
import com.bablsoft.accessflow.sqlreview.api.SqlRuleCondition;
import com.bablsoft.accessflow.sqlreview.internal.rules.condition.SqlRuleConditionCodec;
import com.bablsoft.accessflow.sqlreview.internal.web.model.SqlReviewCustomRuleRequest;
import com.bablsoft.accessflow.sqlreview.internal.web.model.SqlReviewRuleTestRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.http.MockHttpInputMessage;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AdminSqlReviewCustomRuleControllerTest {

    private static final SqlRuleCondition CONDITION = new SqlRuleCondition.FunctionCalled(List.of("dblink"));

    private final SqlReviewCustomRuleService service = mock(SqlReviewCustomRuleService.class);
    private final SqlReviewFindingRenderer renderer = mock(SqlReviewFindingRenderer.class);
    private final AuditLogService auditLogService = mock(AuditLogService.class);
    private final StaticMessageSource messages = messageSource();
    private final SqlRuleConditionCodec codec = new SqlRuleConditionCodec(
            JsonMapper.builder().propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE).build(), messages);
    private final AdminSqlReviewCustomRuleController controller =
            new AdminSqlReviewCustomRuleController(service, codec, renderer, auditLogService, messages);

    private final UUID organizationId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final UUID ruleId = UUID.randomUUID();
    private final RequestAuditContext auditContext = new RequestAuditContext("203.0.113.5", "ua/1");
    private final Authentication authentication = new UsernamePasswordAuthenticationToken(
            JwtClaims.forSystemRole(userId, "admin@x.com", UserRoleType.ADMIN, organizationId), "n/a", List.of());

    private static StaticMessageSource messageSource() {
        var ms = new StaticMessageSource();
        ms.setUseCodeAsDefaultMessage(true);
        return ms;
    }

    @BeforeEach
    void setUp() {
        var request = new MockHttpServletRequest();
        request.setRequestURI("/api/v1/admin/sql-review-rules");
        request.setServerName("localhost");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    private SqlReviewCustomRuleView view(boolean enabled) {
        return new SqlReviewCustomRuleView(ruleId, organizationId, "custom_no_dblink", "No dblink", null,
                "dblink on {tables}", SqlRuleCategory.STATEMENT_SAFETY, SqlReviewSeverity.BLOCK, enabled, CONDITION,
                Instant.now(), Instant.now());
    }

    private static SqlReviewCustomRuleRequest request() {
        var condition = JsonNodeFactory.instance.objectNode().put("type", "function_called");
        condition.putArray("names").add("dblink");
        return new SqlReviewCustomRuleRequest("custom_no_dblink", "No dblink", null, "dblink on {tables}",
                SqlRuleCategory.STATEMENT_SAFETY, SqlReviewSeverity.BLOCK, null, condition);
    }

    private AuditEntry recordedAudit() {
        var captor = ArgumentCaptor.forClass(AuditEntry.class);
        verify(auditLogService).record(captor.capture());
        return captor.getValue();
    }

    @Test
    void theControllerIsGatedByTheSqlReviewManagePermissionOnTheDocumentedPath() {
        var preAuthorize = AdminSqlReviewCustomRuleController.class.getAnnotation(PreAuthorize.class);
        assertThat(preAuthorize).isNotNull();
        assertThat(preAuthorize.value()).isEqualTo("hasAuthority('PERM_SQL_REVIEW_MANAGE')");
        assertThat(AdminSqlReviewCustomRuleController.class.getAnnotation(RequestMapping.class).value())
                .containsExactly("/api/v1/admin/sql-review-rules");
    }

    @Test
    void listMapsViewsWithTheConditionAsJson() {
        when(service.list(organizationId)).thenReturn(List.of(view(true)));

        var result = controller.list(authentication);

        assertThat(result).singleElement().satisfies(rule -> {
            assertThat(rule.ruleId()).isEqualTo("custom_no_dblink");
            assertThat(rule.condition().get("type").asString()).isEqualTo("function_called");
            assertThat(rule.condition().get("names").get(0).asString()).isEqualTo("dblink");
        });
    }

    @Test
    void getMaps() {
        when(service.get(organizationId, ruleId)).thenReturn(view(false));

        var result = controller.get(ruleId, authentication);

        assertThat(result.id()).isEqualTo(ruleId);
        assertThat(result.enabled()).isFalse();
    }

    @Test
    void createDecodesTheConditionReturns201WithLocationAndAudits() {
        when(service.create(eq(organizationId), any(SqlReviewCustomRuleCommand.class))).thenReturn(view(true));

        var response = controller.create(request(), authentication, auditContext);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getHeaders().getLocation()).hasPath("/api/v1/admin/sql-review-rules/" + ruleId);
        var captor = ArgumentCaptor.forClass(SqlReviewCustomRuleCommand.class);
        verify(service).create(eq(organizationId), captor.capture());
        assertThat(captor.getValue().condition()).isEqualTo(CONDITION);
        var audit = recordedAudit();
        assertThat(audit.action()).isEqualTo(AuditAction.SQL_REVIEW_RULE_CREATED);
        assertThat(audit.resourceType()).isEqualTo(AuditResourceType.SQL_REVIEW_RULE);
        assertThat(audit.resourceId()).isEqualTo(ruleId);
        assertThat(audit.actorId()).isEqualTo(userId);
        assertThat(audit.metadata()).containsEntry("rule_id", "custom_no_dblink")
                .containsEntry("category", "STATEMENT_SAFETY")
                .containsEntry("default_severity", "BLOCK")
                .containsEntry("enabled", true);
        assertThat(audit.ipAddress()).isEqualTo("203.0.113.5");
    }

    @Test
    void createWithAnUndecodableConditionNeverReachesTheService() {
        var body = new SqlReviewCustomRuleRequest("custom_no_dblink", "n", null, "m", SqlRuleCategory.PERFORMANCE,
                SqlReviewSeverity.WARN, true, JsonNodeFactory.instance.objectNode().put("type", "nope"));

        assertThatThrownBy(() -> controller.create(body, authentication, auditContext))
                .isInstanceOf(IllegalSqlReviewCustomRuleException.class);
        verifyNoInteractions(service, auditLogService);
    }

    @Test
    void updateDelegatesAndAudits() {
        when(service.update(eq(organizationId), eq(ruleId), any(SqlReviewCustomRuleCommand.class)))
                .thenReturn(view(true));

        var result = controller.update(ruleId, request(), authentication, auditContext);

        assertThat(result.ruleId()).isEqualTo("custom_no_dblink");
        assertThat(recordedAudit().action()).isEqualTo(AuditAction.SQL_REVIEW_RULE_UPDATED);
    }

    @Test
    void deleteReturns204AndAudits() {
        var response = controller.delete(ruleId, authentication, auditContext);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(service).delete(organizationId, ruleId);
        var audit = recordedAudit();
        assertThat(audit.action()).isEqualTo(AuditAction.SQL_REVIEW_RULE_DELETED);
        assertThat(audit.resourceId()).isEqualTo(ruleId);
    }

    @Test
    void aFailedAuditWriteNeverFailsTheMutation() {
        doThrow(new IllegalStateException("audit down")).when(auditLogService).record(any());

        var response = controller.delete(ruleId, authentication, auditContext);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    void testRendersFindingsAndWritesNoAudit() {
        var finding = new SqlReviewFinding("custom_no_dblink", SqlReviewSeverity.BLOCK, 0, 1,
                Map.of("message", "dblink on x"));
        when(service.test(any(SqlReviewCustomRuleCommand.class), eq("SELECT dblink('x')"), eq(DbType.MYSQL)))
                .thenReturn(new SqlReviewResult(true, List.of(finding)));
        when(renderer.message(eq(finding), any(Locale.class))).thenReturn("dblink on x");

        var result = controller.test(new SqlReviewRuleTestRequest(request(), "SELECT dblink('x')", DbType.MYSQL));

        assertThat(result.findings()).singleElement().satisfies(f -> {
            assertThat(f.ruleId()).isEqualTo("custom_no_dblink");
            assertThat(f.severity()).isEqualTo(SqlReviewSeverity.BLOCK);
            assertThat(f.message()).isEqualTo("dblink on x");
        });
        verifyNoInteractions(auditLogService);
    }

    @Test
    void anUnreadableBodyIsA400ValidationError() {
        var problem = controller.handleUnreadableBody(new HttpMessageNotReadableException("bad",
                new MockHttpInputMessage(new byte[0])));

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        assertThat(problem.getProperties()).containsEntry("error", "VALIDATION_ERROR");
        assertThat(problem.getDetail()).isEqualTo("error.sql_review_rule_body_unreadable");
    }
}
