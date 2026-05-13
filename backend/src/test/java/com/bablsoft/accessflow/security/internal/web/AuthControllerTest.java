package com.bablsoft.accessflow.security.internal.web;

import com.bablsoft.accessflow.audit.api.AuditAction;
import com.bablsoft.accessflow.audit.api.AuditEntry;
import com.bablsoft.accessflow.audit.api.AuditLogService;
import com.bablsoft.accessflow.audit.api.RequestAuditContext;
import com.bablsoft.accessflow.core.api.AuthProviderType;
import com.bablsoft.accessflow.core.api.BootstrapService;
import com.bablsoft.accessflow.core.api.SetupResult;
import com.bablsoft.accessflow.core.api.UserQueryService;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.core.api.UserView;
import com.bablsoft.accessflow.security.api.AuthResult;
import com.bablsoft.accessflow.security.api.AuthenticationService;
import com.bablsoft.accessflow.security.api.TotpAuthenticationException;
import com.bablsoft.accessflow.security.api.TotpRequiredException;
import com.bablsoft.accessflow.security.internal.web.model.LoginRequest;
import com.bablsoft.accessflow.security.internal.web.model.SetupRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AuthControllerTest {

    private AuthenticationService authenticationService;
    private AuditLogService auditLogService;
    private UserQueryService userQueryService;
    private BootstrapService bootstrapService;
    private PasswordEncoder passwordEncoder;
    private AuthController controller;

    private final RequestAuditContext auditContext =
            new RequestAuditContext("203.0.113.5", "ua/1");

    @BeforeEach
    void setUp() {
        authenticationService = mock(AuthenticationService.class);
        auditLogService = mock(AuditLogService.class);
        userQueryService = mock(UserQueryService.class);
        bootstrapService = mock(BootstrapService.class);
        passwordEncoder = mock(PasswordEncoder.class);
        controller = new AuthController(authenticationService, auditLogService, userQueryService,
                bootstrapService, passwordEncoder);
    }

    @Test
    void setupStatusDelegatesToBootstrapService() {
        when(bootstrapService.isSetupRequired()).thenReturn(true);
        assertThat(controller.setupStatus().setupRequired()).isTrue();
    }

    @Test
    void setupSwallowsAuditFailure() {
        when(passwordEncoder.encode("Password123!")).thenReturn("hashed");
        var userId = UUID.randomUUID();
        var orgId = UUID.randomUUID();
        when(bootstrapService.performSetup(any())).thenReturn(new SetupResult(orgId, userId));
        when(auditLogService.record(any(AuditEntry.class)))
                .thenThrow(new RuntimeException("audit down"));

        var response = controller.setup(
                new SetupRequest("Acme", "admin@example.com", "Admin", "Password123!"),
                auditContext);

        assertThat(response.getStatusCode().value()).isEqualTo(201);
    }

    @Test
    void loginRecordsSuccessfulLoginAudit() {
        HttpServletResponse response = new MockHttpServletResponse();
        var user = userView(UserRoleType.ANALYST);
        var result = new AuthResult("access", "refresh", "Bearer", 900L, user);
        when(authenticationService.login(any())).thenReturn(result);

        var login = controller.login(
                new LoginRequest("alice@example.com", "Password123!", null), auditContext, response);

        assertThat(login.getStatusCode().value()).isEqualTo(200);
        assertThat(login.getBody().accessToken()).isEqualTo("access");
        verify(auditLogService).record(any(AuditEntry.class));
    }

    @Test
    void loginPropagatesTotpRequiredWithoutAudit() {
        when(authenticationService.login(any()))
                .thenThrow(new TotpRequiredException("totp required"));

        assertThatThrownBy(() -> controller.login(
                new LoginRequest("alice@example.com", "Password123!", null),
                auditContext, new MockHttpServletResponse()))
                .isInstanceOf(TotpRequiredException.class);

        verifyNoInteractions(auditLogService, userQueryService);
    }

    @Test
    void loginRecordsAuditAndRethrowsOnTotpFailure() {
        when(authenticationService.login(any()))
                .thenThrow(new TotpAuthenticationException("bad code"));
        when(userQueryService.findByEmail("alice@example.com"))
                .thenReturn(Optional.of(userView(UserRoleType.ANALYST)));

        assertThatThrownBy(() -> controller.login(
                new LoginRequest("alice@example.com", "Password123!", "000000"),
                auditContext, new MockHttpServletResponse()))
                .isInstanceOf(TotpAuthenticationException.class);

        verify(auditLogService).record(any(AuditEntry.class));
    }

    @Test
    void loginRecordsAuditAndRethrowsOnBadCredentials() {
        when(authenticationService.login(any()))
                .thenThrow(new BadCredentialsException("bad creds"));
        when(userQueryService.findByEmail("alice@example.com"))
                .thenReturn(Optional.of(userView(UserRoleType.ANALYST)));

        assertThatThrownBy(() -> controller.login(
                new LoginRequest("alice@example.com", "Password123!", null),
                auditContext, new MockHttpServletResponse()))
                .isInstanceOf(BadCredentialsException.class);

        verify(auditLogService).record(any(AuditEntry.class));
    }

    @Test
    void loginFailureForUnknownEmailSkipsAuditWrite() {
        when(authenticationService.login(any()))
                .thenThrow(new BadCredentialsException("bad creds"));
        when(userQueryService.findByEmail("ghost@example.com"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.login(
                new LoginRequest("ghost@example.com", "Password123!", null),
                auditContext, new MockHttpServletResponse()))
                .isInstanceOf(BadCredentialsException.class);

        verify(auditLogService, never()).record(any(AuditEntry.class));
    }

    @Test
    void loginFailureSwallowsAuditException() {
        when(authenticationService.login(any()))
                .thenThrow(new BadCredentialsException("bad creds"));
        when(userQueryService.findByEmail("alice@example.com"))
                .thenReturn(Optional.of(userView(UserRoleType.ANALYST)));
        when(auditLogService.record(any(AuditEntry.class)))
                .thenThrow(new RuntimeException("audit down"));

        assertThatThrownBy(() -> controller.login(
                new LoginRequest("alice@example.com", "Password123!", null),
                auditContext, new MockHttpServletResponse()))
                .isInstanceOf(BadCredentialsException.class);
    }

    @Test
    void successfulLoginSwallowsAuditException() {
        var user = userView(UserRoleType.ANALYST);
        when(authenticationService.login(any()))
                .thenReturn(new AuthResult("access", "refresh", "Bearer", 900L, user));
        when(auditLogService.record(any(AuditEntry.class)))
                .thenThrow(new RuntimeException("audit down"));

        var resp = controller.login(
                new LoginRequest("alice@example.com", "Password123!", null),
                auditContext, new MockHttpServletResponse());
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void loginFailureWithNullReasonOmitsReasonMetadata() {
        when(authenticationService.login(any()))
                .thenThrow(new BadCredentialsException(null) {
                    @Override
                    public String getMessage() {
                        return null;
                    }
                });
        when(userQueryService.findByEmail(anyString()))
                .thenReturn(Optional.of(userView(UserRoleType.ANALYST)));

        assertThatThrownBy(() -> controller.login(
                new LoginRequest("alice@example.com", "Password123!", null),
                auditContext, new MockHttpServletResponse()))
                .isInstanceOf(BadCredentialsException.class);

        verify(auditLogService).record(any(AuditEntry.class));
    }

    @Test
    void refreshReturnsUnauthorizedWhenCookieMissing() {
        var resp = controller.refresh(null, new MockHttpServletResponse());
        assertThat(resp.getStatusCode().value()).isEqualTo(401);
        verifyNoInteractions(authenticationService);
    }

    @Test
    void refreshSetsCookieAndReturnsToken() {
        var user = userView(UserRoleType.ANALYST);
        when(authenticationService.refresh("token"))
                .thenReturn(new AuthResult("new-access", "new-refresh", "Bearer", 900L, user));

        var resp = controller.refresh("token", new MockHttpServletResponse());

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody().accessToken()).isEqualTo("new-access");
    }

    @Test
    void logoutClearsCookie() {
        var resp = controller.logout("token", new MockHttpServletResponse());
        assertThat(resp.getStatusCode().value()).isEqualTo(204);
        verify(authenticationService).logout("token");
    }

    private static UserView userView(UserRoleType role) {
        return new UserView(UUID.randomUUID(), "alice@example.com", "Alice", role,
                UUID.randomUUID(), true, AuthProviderType.LOCAL, "hash",
                Instant.now(), "en", false, Instant.now());
    }
}
