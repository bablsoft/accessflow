package com.bablsoft.accessflow.serviceaccounts.internal.web;

import com.bablsoft.accessflow.audit.api.AuditAction;
import com.bablsoft.accessflow.audit.api.AuditEntry;
import com.bablsoft.accessflow.audit.api.AuditLogService;
import com.bablsoft.accessflow.audit.api.AuditResourceType;
import com.bablsoft.accessflow.audit.api.RequestAuditContext;
import com.bablsoft.accessflow.core.api.PageRequest;
import com.bablsoft.accessflow.core.api.PageResponse;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.security.api.JwtClaims;
import com.bablsoft.accessflow.serviceaccounts.api.CreateServiceAccountCommand;
import com.bablsoft.accessflow.serviceaccounts.api.IssueServiceAccountKeyCommand;
import com.bablsoft.accessflow.serviceaccounts.api.RotateServiceAccountKeyCommand;
import com.bablsoft.accessflow.serviceaccounts.api.ServiceAccountAdminService;
import com.bablsoft.accessflow.serviceaccounts.api.ServiceAccountAdminView;
import com.bablsoft.accessflow.serviceaccounts.api.ServiceAccountClearableField;
import com.bablsoft.accessflow.serviceaccounts.api.ServiceAccountIssuedKey;
import com.bablsoft.accessflow.serviceaccounts.api.ServiceAccountKeyView;
import com.bablsoft.accessflow.serviceaccounts.api.ServiceAccountRotatedKey;
import com.bablsoft.accessflow.serviceaccounts.api.ServiceAccountSource;
import com.bablsoft.accessflow.serviceaccounts.api.UpdateServiceAccountCommand;
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

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ServiceAccountControllerTest {

    private final ServiceAccountAdminService service = mock(ServiceAccountAdminService.class);
    private final AuditLogService auditLogService = mock(AuditLogService.class);
    private final ServiceAccountController controller =
            new ServiceAccountController(service, auditLogService, messageSource());

    private final UUID organizationId = UUID.randomUUID();
    private final UUID adminId = UUID.randomUUID();
    private final UUID accountId = UUID.randomUUID();
    private final RequestAuditContext auditContext = new RequestAuditContext("203.0.113.5", "ua/1");
    private final Authentication authentication = new UsernamePasswordAuthenticationToken(
            JwtClaims.forSystemRole(adminId, "admin@x.com", UserRoleType.ADMIN, organizationId), "n/a", List.of());

    private static StaticMessageSource messageSource() {
        var ms = new StaticMessageSource();
        ms.setUseCodeAsDefaultMessage(true);
        return ms;
    }

    @BeforeEach
    void setUp() {
        var request = new MockHttpServletRequest();
        request.setRequestURI("/api/v1/admin/service-accounts");
        request.setServerName("localhost");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void theControllerIsGatedByTheServiceAccountManagePermission() {
        var preAuthorize = ServiceAccountController.class.getAnnotation(PreAuthorize.class);
        assertThat(preAuthorize).isNotNull();
        assertThat(preAuthorize.value()).isEqualTo("hasAuthority('PERM_SERVICE_ACCOUNT_MANAGE')");
    }

    @Test
    void listAdaptsThePageableAndFilter() {
        when(service.list(eq(organizationId), eq(ServiceAccountSource.BOOTSTRAP), any()))
                .thenReturn(new PageResponse<>(List.of(view(List.of())), 1, 5, 6, 2));

        var result = controller.list(ServiceAccountSource.BOOTSTRAP,
                org.springframework.data.domain.PageRequest.of(1, 5), authentication);

        var captor = ArgumentCaptor.forClass(PageRequest.class);
        verify(service).list(eq(organizationId), eq(ServiceAccountSource.BOOTSTRAP), captor.capture());
        assertThat(captor.getValue().page()).isEqualTo(1);
        assertThat(captor.getValue().size()).isEqualTo(5);
        assertThat(result.content()).singleElement().extracting(ServiceAccountResponse::id).isEqualTo(accountId);
        assertThat(result.totalElements()).isEqualTo(6);
        assertThat(result.totalPages()).isEqualTo(2);
    }

    @Test
    void getMapsTheViewWithItsKeys() {
        when(service.get(organizationId, accountId)).thenReturn(view(List.of(key("ci", true))));

        var result = controller.get(accountId, authentication);

        assertThat(result.email()).isEqualTo("bot@example.com");
        assertThat(result.apiKeys()).singleElement().satisfies(k -> {
            assertThat(k.name()).isEqualTo("ci");
            assertThat(k.bootstrapDeclared()).isTrue();
        });
    }

    @Test
    void createReturns201WithLocationAndAudits() {
        var body = new CreateServiceAccountRequest("bot@example.com", "Bot", null, null, "d", null,
                List.of("validate_sql"), 10, null);
        when(service.create(eq(organizationId), any())).thenReturn(view(List.of()));

        var response = controller.create(body, authentication, auditContext);

        var captor = ArgumentCaptor.forClass(CreateServiceAccountCommand.class);
        verify(service).create(eq(organizationId), captor.capture());
        assertThat(captor.getValue().email()).isEqualTo("bot@example.com");
        assertThat(captor.getValue().mcpToolAllowList()).containsExactly("validate_sql");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getHeaders().getLocation()).isNotNull();
        assertThat(response.getHeaders().getLocation().getPath())
                .isEqualTo("/api/v1/admin/service-accounts/" + accountId);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().id()).isEqualTo(accountId);

        var audit = recordedAudit();
        assertThat(audit.action()).isEqualTo(AuditAction.SERVICE_ACCOUNT_CREATED);
        assertThat(audit.resourceType()).isEqualTo(AuditResourceType.SERVICE_ACCOUNT);
        assertThat(audit.resourceId()).isEqualTo(accountId);
        assertThat(audit.organizationId()).isEqualTo(organizationId);
        assertThat(audit.actorId()).isEqualTo(adminId);
        assertThat(audit.ipAddress()).isEqualTo("203.0.113.5");
        assertThat(audit.userAgent()).isEqualTo("ua/1");
        assertThat(audit.metadata()).containsEntry("email", "bot@example.com").containsEntry("role", "READONLY");
    }

    @Test
    void updatePassesTheCallerAsActorAndAuditsThePresentFieldNames() {
        var body = new UpdateServiceAccountRequest("Renamed", null, null, true, "d", null, null, 5, null,
                Set.of(ServiceAccountClearableField.RATE_LIMIT_PER_DAY));
        when(service.update(eq(organizationId), eq(accountId), eq(adminId), any())).thenReturn(view(List.of()));

        var result = controller.update(accountId, body, authentication, auditContext);

        var captor = ArgumentCaptor.forClass(UpdateServiceAccountCommand.class);
        verify(service).update(eq(organizationId), eq(accountId), eq(adminId), captor.capture());
        assertThat(captor.getValue().displayName()).isEqualTo("Renamed");
        assertThat(captor.getValue().active()).isTrue();
        assertThat(result.id()).isEqualTo(accountId);
        var audit = recordedAudit();
        assertThat(audit.action()).isEqualTo(AuditAction.SERVICE_ACCOUNT_UPDATED);
        assertThat(audit.metadata()).containsEntry("fields",
                List.of("display_name", "active", "description", "rate_limit_per_minute"));
        assertThat(audit.metadata()).containsEntry("cleared", List.of("rate_limit_per_day"));
    }

    @Test
    void deactivateReturns204AndAudits() {
        var response = controller.deactivate(accountId, authentication, auditContext);

        verify(service).deactivate(organizationId, accountId, adminId);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        var audit = recordedAudit();
        assertThat(audit.action()).isEqualTo(AuditAction.SERVICE_ACCOUNT_DEACTIVATED);
        assertThat(audit.resourceId()).isEqualTo(accountId);
        assertThat(audit.metadata()).isEmpty();
    }

    @Test
    void issueKeyReturns201WithTheRawKeyAndNeverAuditsIt() {
        var issued = new ServiceAccountIssuedKey(key("ci", false), "af_secret");
        when(service.issueKey(eq(organizationId), eq(accountId), any())).thenReturn(issued);
        var expires = Instant.parse("2027-01-01T00:00:00Z");

        var response = controller.issueKey(accountId, new IssueServiceAccountKeyRequest("ci", expires),
                authentication, auditContext);

        var captor = ArgumentCaptor.forClass(IssueServiceAccountKeyCommand.class);
        verify(service).issueKey(eq(organizationId), eq(accountId), captor.capture());
        assertThat(captor.getValue().expiresAt()).isEqualTo(expires);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getHeaders().getLocation().getPath())
                .isEqualTo("/api/v1/admin/service-accounts/" + accountId);
        assertThat(response.getBody().rawKey()).isEqualTo("af_secret");
        var audit = recordedAudit();
        assertThat(audit.action()).isEqualTo(AuditAction.SERVICE_ACCOUNT_KEY_ISSUED);
        assertThat(audit.metadata()).containsEntry("api_key_id", issued.apiKey().id().toString())
                .containsEntry("name", "ci");
        assertThat(audit.metadata().values()).noneMatch(v -> String.valueOf(v).contains("af_secret"));
    }

    @Test
    void rotateKeyReturns201AndAuditsBothKeyIds() {
        var keyId = UUID.randomUUID();
        var superseded = new ServiceAccountKeyView(keyId, "ci", "af_old", false, Instant.EPOCH, null,
                Instant.parse("2026-09-18T10:00:00Z"), null);
        var rotated = new ServiceAccountRotatedKey(key("ci-2", false), "af_new", superseded);
        when(service.rotateKey(eq(organizationId), eq(accountId), eq(keyId), any())).thenReturn(rotated);

        var response = controller.rotateKey(accountId, keyId,
                new RotateServiceAccountKeyRequest("ci-2", null, Duration.ofHours(1)), authentication, auditContext);

        var captor = ArgumentCaptor.forClass(RotateServiceAccountKeyCommand.class);
        verify(service).rotateKey(eq(organizationId), eq(accountId), eq(keyId), captor.capture());
        assertThat(captor.getValue().gracePeriod()).isEqualTo(Duration.ofHours(1));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody().rawKey()).isEqualTo("af_new");
        assertThat(response.getBody().supersededKey().expiresAt()).isEqualTo(Instant.parse("2026-09-18T10:00:00Z"));
        var audit = recordedAudit();
        assertThat(audit.action()).isEqualTo(AuditAction.SERVICE_ACCOUNT_KEY_ROTATED);
        assertThat(audit.metadata()).containsEntry("api_key_id", rotated.apiKey().id().toString())
                .containsEntry("superseded_key_id", keyId.toString())
                .containsEntry("name", "ci-2")
                .containsEntry("superseded_expires_at", "2026-09-18T10:00:00Z");
        assertThat(audit.metadata().values()).noneMatch(v -> String.valueOf(v).contains("af_new"));
    }

    @Test
    void revokeKeyReturns204AndAudits() {
        var keyId = UUID.randomUUID();

        var response = controller.revokeKey(accountId, keyId, authentication, auditContext);

        verify(service).revokeKey(organizationId, accountId, keyId);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        var audit = recordedAudit();
        assertThat(audit.action()).isEqualTo(AuditAction.SERVICE_ACCOUNT_KEY_REVOKED);
        assertThat(audit.metadata()).containsEntry("api_key_id", keyId.toString());
    }

    @Test
    void anUnreadableBodyIsA400ValidationError() {
        var pd = controller.handleUnreadableInput(new HttpMessageNotReadableException("bad enum",
                new MockHttpInputMessage(new byte[0])));

        assertThat(pd.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        assertThat(pd.getProperties()).containsEntry("error", "VALIDATION_ERROR");
        assertThat(pd.getProperties()).containsKey("timestamp");
        assertThat(pd.getDetail()).isEqualTo("error.service_account_body_unreadable");
    }

    @Test
    void anAuditWriteFailureNeverFailsTheRequest() {
        doThrow(new IllegalStateException("audit down")).when(auditLogService).record(any());

        var response = controller.deactivate(accountId, authentication, auditContext);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(service).deactivate(organizationId, accountId, adminId);
    }

    private AuditEntry recordedAudit() {
        var captor = ArgumentCaptor.forClass(AuditEntry.class);
        verify(auditLogService).record(captor.capture());
        return captor.getValue();
    }

    private ServiceAccountAdminView view(List<ServiceAccountKeyView> keys) {
        return new ServiceAccountAdminView(accountId, organizationId, "bot@example.com", "Bot",
                UserRoleType.READONLY, UUID.randomUUID(), "READONLY", true, ServiceAccountSource.UI, "d", null,
                List.of("validate_sql"), 10, null, keys.size(), null, null, Instant.EPOCH, Instant.EPOCH, keys);
    }

    private static ServiceAccountKeyView key(String name, boolean declared) {
        return new ServiceAccountKeyView(UUID.randomUUID(), name, "af_" + name, declared, Instant.EPOCH,
                null, null, null);
    }
}
