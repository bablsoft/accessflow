package com.bablsoft.accessflow.sqlreview.internal.web;

import com.bablsoft.accessflow.audit.api.AuditAction;
import com.bablsoft.accessflow.audit.api.AuditEntry;
import com.bablsoft.accessflow.audit.api.AuditLogService;
import com.bablsoft.accessflow.audit.api.AuditResourceType;
import com.bablsoft.accessflow.audit.api.RequestAuditContext;
import com.bablsoft.accessflow.core.api.DatasourceEnvironment;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.security.api.JwtClaims;
import com.bablsoft.accessflow.sqlreview.api.CreateSqlReviewRulesetCommand;
import com.bablsoft.accessflow.sqlreview.api.SqlReviewRuleConfigView;
import com.bablsoft.accessflow.sqlreview.api.SqlReviewRulesetService;
import com.bablsoft.accessflow.sqlreview.api.SqlReviewRulesetView;
import com.bablsoft.accessflow.sqlreview.api.SqlReviewSeverity;
import com.bablsoft.accessflow.sqlreview.api.UpdateSqlReviewRulesetCommand;
import com.bablsoft.accessflow.sqlreview.internal.web.model.CreateSqlReviewRulesetRequest;
import com.bablsoft.accessflow.sqlreview.internal.web.model.SqlReviewRuleConfigRequest;
import com.bablsoft.accessflow.sqlreview.internal.web.model.UpdateSqlReviewRulesetRequest;
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
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminSqlReviewRulesetControllerTest {

    private final SqlReviewRulesetService service = mock(SqlReviewRulesetService.class);
    private final AuditLogService auditLogService = mock(AuditLogService.class);
    private final AdminSqlReviewRulesetController controller =
            new AdminSqlReviewRulesetController(service, auditLogService, messageSource());

    private final UUID organizationId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final UUID rulesetId = UUID.randomUUID();
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
        request.setRequestURI("/api/v1/admin/sql-review-rulesets");
        request.setServerName("localhost");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    private SqlReviewRulesetView view(String name, DatasourceEnvironment environment) {
        return new SqlReviewRulesetView(rulesetId, organizationId, name, null, environment, true,
                List.of(new SqlReviewRuleConfigView("protected_table", SqlReviewSeverity.BLOCK,
                        Map.of("globs", List.of("payroll.*")))),
                Instant.now(), Instant.now());
    }

    private AuditEntry recordedAudit() {
        var captor = ArgumentCaptor.forClass(AuditEntry.class);
        verify(auditLogService).record(captor.capture());
        return captor.getValue();
    }

    @Test
    void theControllerIsGatedByTheSqlReviewManagePermission() {
        var preAuthorize = AdminSqlReviewRulesetController.class.getAnnotation(PreAuthorize.class);

        assertThat(preAuthorize).isNotNull();
        assertThat(preAuthorize.value()).isEqualTo("hasAuthority('PERM_SQL_REVIEW_MANAGE')");
    }

    @Test
    void listMapsViewsToResponses() {
        when(service.list(organizationId)).thenReturn(List.of(view("Production", DatasourceEnvironment.PRODUCTION)));

        var result = controller.list(authentication);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).name()).isEqualTo("Production");
        assertThat(result.get(0).environment()).isEqualTo(DatasourceEnvironment.PRODUCTION);
        assertThat(result.get(0).rules()).singleElement().satisfies(rule -> {
            assertThat(rule.ruleId()).isEqualTo("protected_table");
            assertThat(rule.params()).containsEntry("globs", List.of("payroll.*"));
        });
    }

    @Test
    void getMaps() {
        when(service.get(organizationId, rulesetId)).thenReturn(view("Default", null));

        var result = controller.get(rulesetId, authentication);

        assertThat(result.id()).isEqualTo(rulesetId);
        assertThat(result.environment()).isNull();
    }

    @Test
    void createReturns201WithLocationAndAudits() {
        when(service.create(eq(organizationId), any(CreateSqlReviewRulesetCommand.class)))
                .thenReturn(view("Production", DatasourceEnvironment.PRODUCTION));
        var body = new CreateSqlReviewRulesetRequest("Production", null, DatasourceEnvironment.PRODUCTION, null,
                List.of(new SqlReviewRuleConfigRequest("protected_table", SqlReviewSeverity.BLOCK,
                        Map.of("globs", List.of("payroll.*")))));

        var response = controller.create(body, authentication, auditContext);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getHeaders().getLocation()).hasPath("/api/v1/admin/sql-review-rulesets/" + rulesetId);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().name()).isEqualTo("Production");
        var captor = ArgumentCaptor.forClass(CreateSqlReviewRulesetCommand.class);
        verify(service).create(eq(organizationId), captor.capture());
        assertThat(captor.getValue().rules()).singleElement()
                .satisfies(rule -> assertThat(rule.params()).containsEntry("globs", List.of("payroll.*")));
        var audit = recordedAudit();
        assertThat(audit.action()).isEqualTo(AuditAction.SQL_REVIEW_RULESET_CREATED);
        assertThat(audit.resourceType()).isEqualTo(AuditResourceType.SQL_REVIEW_RULESET);
        assertThat(audit.resourceId()).isEqualTo(rulesetId);
        assertThat(audit.organizationId()).isEqualTo(organizationId);
        assertThat(audit.actorId()).isEqualTo(userId);
        assertThat(audit.ipAddress()).isEqualTo("203.0.113.5");
        assertThat(audit.metadata()).containsEntry("name", "Production")
                .containsEntry("environment", "PRODUCTION")
                .containsEntry("enabled", true)
                .containsEntry("rule_count", 1);
    }

    @Test
    void updateDelegatesWithTotalSemanticsAndAudits() {
        when(service.update(eq(organizationId), eq(rulesetId), any(UpdateSqlReviewRulesetCommand.class)))
                .thenReturn(view("Renamed", null));
        var body = new UpdateSqlReviewRulesetRequest("Renamed", null, null, null, null);

        var response = controller.update(rulesetId, body, authentication, auditContext);

        assertThat(response.name()).isEqualTo("Renamed");
        var captor = ArgumentCaptor.forClass(UpdateSqlReviewRulesetCommand.class);
        verify(service).update(eq(organizationId), eq(rulesetId), captor.capture());
        assertThat(captor.getValue().clearEnvironment()).isTrue();
        assertThat(captor.getValue().description()).isEmpty();
        assertThat(captor.getValue().enabled()).isTrue();
        assertThat(captor.getValue().rules()).isEmpty();
        var audit = recordedAudit();
        assertThat(audit.action()).isEqualTo(AuditAction.SQL_REVIEW_RULESET_UPDATED);
        assertThat(audit.metadata()).containsEntry("environment", "DEFAULT");
    }

    @Test
    void deleteReturns204AndAudits() {
        var response = controller.delete(rulesetId, authentication, auditContext);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(service).delete(organizationId, rulesetId);
        var audit = recordedAudit();
        assertThat(audit.action()).isEqualTo(AuditAction.SQL_REVIEW_RULESET_DELETED);
        assertThat(audit.resourceId()).isEqualTo(rulesetId);
        assertThat(audit.metadata()).isEmpty();
    }

    @Test
    void anAuditWriteFailureNeverFailsTheRequest() {
        doThrow(new IllegalStateException("audit down")).when(auditLogService).record(any(AuditEntry.class));

        var response = controller.delete(rulesetId, authentication, auditContext);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(service).delete(organizationId, rulesetId);
    }

    @Test
    void unreadableBodyIsA400ValidationError() {
        var ex = new HttpMessageNotReadableException("bad enum", new MockHttpInputMessage(new byte[0]));

        var pd = controller.handleUnreadableBody(ex);

        assertThat(pd.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        assertThat(pd.getDetail()).isEqualTo("error.sql_review_ruleset_body_unreadable");
        assertThat(pd.getProperties()).containsEntry("error", "VALIDATION_ERROR").containsKey("timestamp");
    }
}
