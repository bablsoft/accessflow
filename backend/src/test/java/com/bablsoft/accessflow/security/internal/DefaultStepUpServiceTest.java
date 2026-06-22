package com.bablsoft.accessflow.security.internal;

import com.bablsoft.accessflow.core.api.AuthProviderType;
import com.bablsoft.accessflow.core.api.TotpVerificationService;
import com.bablsoft.accessflow.core.api.UserQueryService;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.core.api.UserView;
import com.bablsoft.accessflow.security.api.StepUpRequiredException;
import com.bablsoft.accessflow.security.api.StepUpVerificationException;
import com.bablsoft.accessflow.security.internal.config.StepUpProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DefaultStepUpServiceTest {

    private UserQueryService userQueryService;
    private TotpVerificationService totpVerificationService;
    private PasswordEncoder passwordEncoder;
    private StepUpCodeStore codeStore;
    private DefaultStepUpService service;

    private final UUID userId = UUID.randomUUID();
    private final Instant now = Instant.parse("2026-06-22T12:00:00Z");

    @BeforeEach
    void setUp() {
        userQueryService = mock(UserQueryService.class);
        totpVerificationService = mock(TotpVerificationService.class);
        passwordEncoder = mock(PasswordEncoder.class);
        codeStore = mock(StepUpCodeStore.class);
        service = new DefaultStepUpService(userQueryService, totpVerificationService, passwordEncoder,
                codeStore, new StepUpProperties(Duration.ofMinutes(5)),
                Clock.fixed(now, ZoneOffset.UTC));
    }

    private UserView user(String passwordHash) {
        return new UserView(userId, "rev@acme.test", "Rev", UserRoleType.REVIEWER, UUID.randomUUID(),
                true, AuthProviderType.LOCAL, passwordHash, null, "en", false, now);
    }

    @Test
    void issuesTokenWhenPasswordMatches() {
        when(userQueryService.findByEmail("rev@acme.test")).thenReturn(Optional.of(user("hash")));
        when(totpVerificationService.isEnabled(userId)).thenReturn(false);
        when(passwordEncoder.matches("pw", "hash")).thenReturn(true);
        when(codeStore.issue(userId)).thenReturn("tok");

        var token = service.issue(userId, "rev@acme.test", "pw", null);

        assertThat(token.token()).isEqualTo("tok");
        assertThat(token.expiresAt()).isEqualTo(now.plus(Duration.ofMinutes(5)));
    }

    @Test
    void issuesTokenWhenTotpMatchesFor2faUser() {
        when(userQueryService.findByEmail("rev@acme.test")).thenReturn(Optional.of(user("hash")));
        when(totpVerificationService.isEnabled(userId)).thenReturn(true);
        when(totpVerificationService.verify(userId, "123456")).thenReturn(true);
        when(codeStore.issue(userId)).thenReturn("tok");

        var token = service.issue(userId, "rev@acme.test", null, "123456");

        assertThat(token.token()).isEqualTo("tok");
        verify(passwordEncoder, never()).matches(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void rejectsWrongPassword() {
        when(userQueryService.findByEmail("rev@acme.test")).thenReturn(Optional.of(user("hash")));
        when(totpVerificationService.isEnabled(userId)).thenReturn(false);
        when(passwordEncoder.matches("bad", "hash")).thenReturn(false);

        assertThatThrownBy(() -> service.issue(userId, "rev@acme.test", "bad", null))
                .isInstanceOf(StepUpVerificationException.class);
        verify(codeStore, never()).issue(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void rejectsWrongTotp() {
        when(userQueryService.findByEmail("rev@acme.test")).thenReturn(Optional.of(user("hash")));
        when(totpVerificationService.isEnabled(userId)).thenReturn(true);
        when(totpVerificationService.verify(userId, "000000")).thenReturn(false);

        assertThatThrownBy(() -> service.issue(userId, "rev@acme.test", null, "000000"))
                .isInstanceOf(StepUpVerificationException.class);
    }

    @Test
    void rejectsTotpCodeWhen2faNotEnrolled() {
        when(userQueryService.findByEmail("rev@acme.test")).thenReturn(Optional.of(user("hash")));
        when(totpVerificationService.isEnabled(userId)).thenReturn(false);

        assertThatThrownBy(() -> service.issue(userId, "rev@acme.test", null, "123456"))
                .isInstanceOf(StepUpVerificationException.class);
    }

    @Test
    void rejectsSsoUserWithoutPasswordOrTotp() {
        when(userQueryService.findByEmail("rev@acme.test")).thenReturn(Optional.of(user(null)));
        when(totpVerificationService.isEnabled(userId)).thenReturn(false);

        assertThatThrownBy(() -> service.issue(userId, "rev@acme.test", "pw", null))
                .isInstanceOf(StepUpVerificationException.class);
    }

    @Test
    void rejectsWhenUserIdDoesNotMatchToken() {
        when(userQueryService.findByEmail("rev@acme.test")).thenReturn(Optional.of(user("hash")));

        assertThatThrownBy(() -> service.issue(UUID.randomUUID(), "rev@acme.test", "pw", null))
                .isInstanceOf(StepUpVerificationException.class);
    }

    @Test
    void rejectsUnknownEmail() {
        when(userQueryService.findByEmail("ghost@acme.test")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.issue(userId, "ghost@acme.test", "pw", null))
                .isInstanceOf(StepUpVerificationException.class);
    }

    @Test
    void consumeReturnsUserId() {
        when(codeStore.consume("tok")).thenReturn(Optional.of(userId));

        assertThat(service.consume("tok")).isEqualTo(userId);
    }

    @Test
    void consumeThrowsWhenTokenMissing() {
        when(codeStore.consume("gone")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.consume("gone"))
                .isInstanceOf(StepUpRequiredException.class);
    }
}
