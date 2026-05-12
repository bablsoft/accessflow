package com.bablsoft.accessflow.security.internal;

import com.bablsoft.accessflow.core.api.AuthProviderType;
import com.bablsoft.accessflow.core.api.TotpVerificationService;
import com.bablsoft.accessflow.core.api.UserQueryService;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.core.api.UserView;
import com.bablsoft.accessflow.security.api.LoginCommand;
import com.bablsoft.accessflow.security.api.JwtClaims;
import com.bablsoft.accessflow.security.api.TotpAuthenticationException;
import com.bablsoft.accessflow.security.api.TotpRequiredException;
import com.bablsoft.accessflow.security.internal.config.JwtProperties;
import com.bablsoft.accessflow.security.internal.jwt.JwtService;
import com.bablsoft.accessflow.security.internal.token.RefreshTokenStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LocalAuthenticationServiceTest {

    @Mock UserQueryService userQueryService;
    @Mock PasswordEncoder passwordEncoder;
    @Mock JwtService jwtService;
    @Mock RefreshTokenStore refreshTokenStore;
    @Mock TotpVerificationService totpVerificationService;

    private LocalAuthenticationService service;
    private UserView activeUser;
    private final UUID userId = UUID.randomUUID();
    private final UUID orgId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        var props = new JwtProperties("", Duration.ofMinutes(15), Duration.ofDays(7));
        service = new LocalAuthenticationService(userQueryService, passwordEncoder,
                jwtService, refreshTokenStore, props, totpVerificationService);
        activeUser = new UserView(userId, "alice@example.com", "Alice",
                UserRoleType.ANALYST, orgId, true, AuthProviderType.LOCAL, "hashed",
                null, null, false, null);
    }

    @Test
    void loginSuccessReturnsTokenPairAndStoresRefreshToken() {
        when(userQueryService.findByEmail("alice@example.com")).thenReturn(Optional.of(activeUser));
        when(passwordEncoder.matches("secret", "hashed")).thenReturn(true);
        when(jwtService.generateAccessToken(activeUser)).thenReturn("access-token");
        when(jwtService.generateRefreshToken(activeUser)).thenReturn("refresh-token");

        var result = service.login(new LoginCommand("alice@example.com", "secret"));

        assertThat(result.accessToken()).isEqualTo("access-token");
        assertThat(result.refreshToken()).isEqualTo("refresh-token");
        assertThat(result.tokenType()).isEqualTo("Bearer");
        verify(refreshTokenStore).store(eq("refresh-token"), eq(userId.toString()), anyLong());
    }

    @Test
    void loginWithUnknownEmailThrows() {
        when(userQueryService.findByEmail(anyString())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.login(new LoginCommand("nobody@example.com", "pass")))
                .isInstanceOf(BadCredentialsException.class);
        verify(refreshTokenStore, never()).store(anyString(), anyString(), anyLong());
    }

    @Test
    void loginWithWrongPasswordThrows() {
        when(userQueryService.findByEmail("alice@example.com")).thenReturn(Optional.of(activeUser));
        when(passwordEncoder.matches(anyString(), anyString())).thenReturn(false);

        assertThatThrownBy(() -> service.login(new LoginCommand("alice@example.com", "wrong")))
                .isInstanceOf(BadCredentialsException.class);
    }

    @Test
    void loginWith2faRequiredAndNoCodeThrowsTotpRequired() {
        when(userQueryService.findByEmail("alice@example.com")).thenReturn(Optional.of(activeUser));
        when(passwordEncoder.matches("secret", "hashed")).thenReturn(true);
        when(totpVerificationService.isEnabled(userId)).thenReturn(true);

        assertThatThrownBy(() -> service.login(new LoginCommand("alice@example.com", "secret")))
                .isInstanceOf(TotpRequiredException.class);
        verify(refreshTokenStore, never()).store(anyString(), anyString(), anyLong());
    }

    @Test
    void loginWith2faAndInvalidCodeThrowsTotpAuthentication() {
        when(userQueryService.findByEmail("alice@example.com")).thenReturn(Optional.of(activeUser));
        when(passwordEncoder.matches("secret", "hashed")).thenReturn(true);
        when(totpVerificationService.isEnabled(userId)).thenReturn(true);
        when(totpVerificationService.verify(userId, "000000")).thenReturn(false);

        assertThatThrownBy(() -> service.login(
                new LoginCommand("alice@example.com", "secret", "000000")))
                .isInstanceOf(TotpAuthenticationException.class);
        verify(refreshTokenStore, never()).store(anyString(), anyString(), anyLong());
    }

    @Test
    void loginWith2faAndValidCodeSucceeds() {
        when(userQueryService.findByEmail("alice@example.com")).thenReturn(Optional.of(activeUser));
        when(passwordEncoder.matches("secret", "hashed")).thenReturn(true);
        when(totpVerificationService.isEnabled(userId)).thenReturn(true);
        when(totpVerificationService.verify(userId, "123456")).thenReturn(true);
        when(jwtService.generateAccessToken(activeUser)).thenReturn("access-token");
        when(jwtService.generateRefreshToken(activeUser)).thenReturn("refresh-token");

        var result = service.login(new LoginCommand("alice@example.com", "secret", "123456"));

        assertThat(result.accessToken()).isEqualTo("access-token");
        verify(refreshTokenStore).store(eq("refresh-token"), eq(userId.toString()), anyLong());
    }

    @Test
    void loginWithInactiveUserThrows() {
        var inactiveUser = new UserView(userId, "alice@example.com", "Alice",
                UserRoleType.ANALYST, orgId, false, AuthProviderType.LOCAL, "hashed",
                null, null, false, null);
        when(userQueryService.findByEmail("alice@example.com")).thenReturn(Optional.of(inactiveUser));

        assertThatThrownBy(() -> service.login(new LoginCommand("alice@example.com", "secret")))
                .isInstanceOf(DisabledException.class);
    }

    @Test
    void refreshSuccessRotatesToken() {
        var claims = new JwtClaims(userId, "alice@example.com", UserRoleType.ANALYST, orgId);
        when(refreshTokenStore.isRevoked("old-refresh")).thenReturn(false);
        when(jwtService.parseRefreshToken("old-refresh")).thenReturn(claims);
        when(userQueryService.findById(userId)).thenReturn(Optional.of(activeUser));
        when(jwtService.generateAccessToken(activeUser)).thenReturn("new-access");
        when(jwtService.generateRefreshToken(activeUser)).thenReturn("new-refresh");

        var result = service.refresh("old-refresh");

        assertThat(result.accessToken()).isEqualTo("new-access");
        verify(refreshTokenStore).revoke("old-refresh");
        verify(refreshTokenStore).store(eq("new-refresh"), eq(userId.toString()), anyLong());
    }

    @Test
    void refreshWithMissingUserThrowsBadCredentials() {
        var claims = new JwtClaims(userId, "alice@example.com", UserRoleType.ANALYST, orgId);
        when(refreshTokenStore.isRevoked("old-refresh")).thenReturn(false);
        when(jwtService.parseRefreshToken("old-refresh")).thenReturn(claims);
        when(userQueryService.findById(userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.refresh("old-refresh"))
                .isInstanceOf(BadCredentialsException.class);
        verify(refreshTokenStore, never()).store(anyString(), anyString(), anyLong());
    }

    @Test
    void refreshWithInactiveUserThrowsDisabled() {
        var claims = new JwtClaims(userId, "alice@example.com", UserRoleType.ANALYST, orgId);
        var inactiveUser = new UserView(userId, "alice@example.com", "Alice",
                UserRoleType.ANALYST, orgId, false, AuthProviderType.LOCAL, "hashed",
                null, null, false, null);
        when(refreshTokenStore.isRevoked("old-refresh")).thenReturn(false);
        when(jwtService.parseRefreshToken("old-refresh")).thenReturn(claims);
        when(userQueryService.findById(userId)).thenReturn(Optional.of(inactiveUser));

        assertThatThrownBy(() -> service.refresh("old-refresh"))
                .isInstanceOf(DisabledException.class);
    }

    @Test
    void refreshWithInvalidJwtThrowsBadCredentials() throws Exception {
        when(refreshTokenStore.isRevoked("bad-refresh")).thenReturn(false);
        when(jwtService.parseRefreshToken("bad-refresh"))
                .thenThrow(new com.bablsoft.accessflow.security.internal.jwt.JwtValidationException("malformed"));

        assertThatThrownBy(() -> service.refresh("bad-refresh"))
                .isInstanceOf(BadCredentialsException.class);
    }

    @Test
    void refreshWithRevokedTokenThrows() {
        when(refreshTokenStore.isRevoked("bad-token")).thenReturn(true);

        assertThatThrownBy(() -> service.refresh("bad-token"))
                .isInstanceOf(BadCredentialsException.class);
        verify(jwtService, never()).parseRefreshToken(anyString());
    }

    @Test
    void logoutRevokesToken() {
        service.logout("some-refresh-token");

        verify(refreshTokenStore).revoke("some-refresh-token");
    }

    @Test
    void logoutWithNullTokenIsIdempotent() {
        service.logout(null);

        verify(refreshTokenStore, never()).revoke(any());
    }
}
