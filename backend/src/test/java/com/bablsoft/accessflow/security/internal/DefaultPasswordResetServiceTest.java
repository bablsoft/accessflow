package com.bablsoft.accessflow.security.internal;

import com.bablsoft.accessflow.core.api.AuthProviderType;
import com.bablsoft.accessflow.core.api.OrganizationLookupService;
import com.bablsoft.accessflow.core.api.SystemSmtpNotConfiguredException;
import com.bablsoft.accessflow.core.api.SystemSmtpSendingConfig;
import com.bablsoft.accessflow.core.api.SystemSmtpService;
import com.bablsoft.accessflow.core.api.UserProfileService;
import com.bablsoft.accessflow.core.api.UserQueryService;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.core.api.UserView;
import com.bablsoft.accessflow.security.api.PasswordResetStatusType;
import com.bablsoft.accessflow.security.api.PasswordResetTokenAlreadyUsedException;
import com.bablsoft.accessflow.security.api.PasswordResetTokenExpiredException;
import com.bablsoft.accessflow.security.api.PasswordResetTokenNotFoundException;
import com.bablsoft.accessflow.security.api.PasswordResetTokenRevokedException;
import com.bablsoft.accessflow.security.internal.config.PasswordResetProperties;
import com.bablsoft.accessflow.security.internal.persistence.entity.PasswordResetTokenEntity;
import com.bablsoft.accessflow.security.internal.persistence.repo.PasswordResetTokenRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.MessageSource;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.thymeleaf.spring6.SpringTemplateEngine;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultPasswordResetServiceTest {

    @Mock PasswordResetTokenRepository repository;
    @Mock UserQueryService userQueryService;
    @Mock UserProfileService userProfileService;
    @Mock SystemSmtpService systemSmtpService;
    @Mock OrganizationLookupService organizationLookupService;
    @Mock SpringTemplateEngine templateEngine;

    private DefaultPasswordResetService service;
    private MessageSource messageSource;

    private final UUID orgId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        var props = new PasswordResetProperties(Duration.ofHours(1),
                URI.create("https://app.example.test"));
        var ms = new StaticMessageSource();
        ms.addMessage("validation.new_password.size", Locale.US,
                "Password must be between 8 and 128 characters");
        ms.setUseCodeAsDefaultMessage(true);
        messageSource = ms;
        service = new DefaultPasswordResetService(repository, userQueryService, userProfileService,
                systemSmtpService, organizationLookupService, templateEngine, props, messageSource);
    }

    private UserView eligibleUser() {
        return new UserView(userId, "alice@example.com", "Alice", UserRoleType.ANALYST,
                orgId, true, AuthProviderType.LOCAL, "hashed",
                Instant.now(), "en", false, Instant.now());
    }

    private UserView ssoUser() {
        return new UserView(userId, "alice@example.com", "Alice", UserRoleType.ANALYST,
                orgId, true, AuthProviderType.OAUTH2, null,
                Instant.now(), "en", false, Instant.now());
    }

    private UserView inactiveUser() {
        return new UserView(userId, "alice@example.com", "Alice", UserRoleType.ANALYST,
                orgId, false, AuthProviderType.LOCAL, "hashed",
                Instant.now(), "en", false, Instant.now());
    }

    private UserView nullHashUser() {
        return new UserView(userId, "alice@example.com", "Alice", UserRoleType.ANALYST,
                orgId, true, AuthProviderType.LOCAL, null,
                Instant.now(), "en", false, Instant.now());
    }

    private void smtpConfigured() {
        when(systemSmtpService.resolveSendingConfig(orgId)).thenReturn(Optional.of(
                new SystemSmtpSendingConfig(orgId, "h", 587, "u", "p", true, "f@x.com", "F")));
    }

    private void smtpNotConfigured() {
        when(systemSmtpService.resolveSendingConfig(orgId)).thenReturn(Optional.empty());
    }

    private static String sha256Hex(String input) throws Exception {
        var digest = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(digest.digest(input.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void requestResetSilentlyNoopsForNullEmail() {
        service.requestReset(null);

        verifyNoInteractions(userQueryService, systemSmtpService, repository);
    }

    @Test
    void requestResetSilentlyNoopsForBlankEmail() {
        service.requestReset("   ");

        verifyNoInteractions(userQueryService, systemSmtpService, repository);
    }

    @Test
    void requestResetSilentlyNoopsForUnknownEmail() {
        when(userQueryService.findByEmail("ghost@example.com")).thenReturn(Optional.empty());

        service.requestReset("ghost@example.com");

        verify(repository, never()).save(any());
        verifyNoInteractions(systemSmtpService);
    }

    @Test
    void requestResetSilentlyNoopsForSsoUser() {
        when(userQueryService.findByEmail("alice@example.com"))
                .thenReturn(Optional.of(ssoUser()));

        service.requestReset("alice@example.com");

        verify(repository, never()).save(any());
        verifyNoInteractions(systemSmtpService);
    }

    @Test
    void requestResetSilentlyNoopsForInactiveUser() {
        when(userQueryService.findByEmail("alice@example.com"))
                .thenReturn(Optional.of(inactiveUser()));

        service.requestReset("alice@example.com");

        verify(repository, never()).save(any());
    }

    @Test
    void requestResetSilentlyNoopsForNullPasswordHash() {
        when(userQueryService.findByEmail("alice@example.com"))
                .thenReturn(Optional.of(nullHashUser()));

        service.requestReset("alice@example.com");

        verify(repository, never()).save(any());
    }

    @Test
    void requestResetSilentlyNoopsWhenSmtpNotConfigured() {
        when(userQueryService.findByEmail("alice@example.com"))
                .thenReturn(Optional.of(eligibleUser()));
        smtpNotConfigured();

        service.requestReset("alice@example.com");

        verify(repository, never()).save(any());
        verify(systemSmtpService, never()).sendSystemEmail(any(), any(), any(), any());
    }

    @Test
    void requestResetIssuesTokenAndSendsEmailForEligibleUser() {
        when(userQueryService.findByEmail("alice@example.com"))
                .thenReturn(Optional.of(eligibleUser()));
        smtpConfigured();
        when(repository.findFirstByUserIdAndStatus(userId, PasswordResetStatusType.PENDING))
                .thenReturn(Optional.empty());
        when(repository.saveAndFlush(any(PasswordResetTokenEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(organizationLookupService.findNameById(orgId)).thenReturn(Optional.of("Acme"));
        when(templateEngine.process(anyString(), any())).thenReturn("<html/>");

        service.requestReset("alice@example.com");

        var captor = ArgumentCaptor.forClass(PasswordResetTokenEntity.class);
        verify(repository).saveAndFlush(captor.capture());
        var entity = captor.getValue();
        assertThat(entity.getStatus()).isEqualTo(PasswordResetStatusType.PENDING);
        assertThat(entity.getUserId()).isEqualTo(userId);
        assertThat(entity.getOrganizationId()).isEqualTo(orgId);
        assertThat(entity.getTokenHash()).hasSize(64);
        verify(systemSmtpService).sendSystemEmail(eq(orgId), eq("alice@example.com"), anyString(), anyString());
    }

    @Test
    void requestResetRevokesPriorPendingToken() {
        when(userQueryService.findByEmail("alice@example.com"))
                .thenReturn(Optional.of(eligibleUser()));
        smtpConfigured();
        var existing = new PasswordResetTokenEntity();
        existing.setId(UUID.randomUUID());
        existing.setUserId(userId);
        existing.setStatus(PasswordResetStatusType.PENDING);
        existing.setExpiresAt(Instant.now().plusSeconds(600));
        when(repository.findFirstByUserIdAndStatus(userId, PasswordResetStatusType.PENDING))
                .thenReturn(Optional.of(existing));
        when(repository.saveAndFlush(any(PasswordResetTokenEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(organizationLookupService.findNameById(orgId)).thenReturn(Optional.empty());
        when(templateEngine.process(anyString(), any())).thenReturn("<html/>");

        service.requestReset("alice@example.com");

        assertThat(existing.getStatus()).isEqualTo(PasswordResetStatusType.REVOKED);
        assertThat(existing.getRevokedAt()).isNotNull();
        verify(repository, org.mockito.Mockito.times(2)).saveAndFlush(any(PasswordResetTokenEntity.class));
    }

    @Test
    void requestResetSwallowsDataIntegrityViolation() {
        when(userQueryService.findByEmail("alice@example.com"))
                .thenReturn(Optional.of(eligibleUser()));
        smtpConfigured();
        when(repository.findFirstByUserIdAndStatus(userId, PasswordResetStatusType.PENDING))
                .thenReturn(Optional.empty());
        when(repository.saveAndFlush(any(PasswordResetTokenEntity.class)))
                .thenThrow(new DataIntegrityViolationException("dup"));

        service.requestReset("alice@example.com");

        verify(systemSmtpService, never()).sendSystemEmail(any(), any(), any(), any());
    }

    @Test
    void requestResetSwallowsSmtpFailure() {
        when(userQueryService.findByEmail("alice@example.com"))
                .thenReturn(Optional.of(eligibleUser()));
        smtpConfigured();
        when(repository.findFirstByUserIdAndStatus(userId, PasswordResetStatusType.PENDING))
                .thenReturn(Optional.empty());
        when(repository.saveAndFlush(any(PasswordResetTokenEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(organizationLookupService.findNameById(orgId)).thenReturn(Optional.empty());
        when(templateEngine.process(anyString(), any())).thenReturn("<html/>");
        org.mockito.Mockito.doThrow(new SystemSmtpNotConfiguredException())
                .when(systemSmtpService).sendSystemEmail(any(), any(), any(), any());

        service.requestReset("alice@example.com");
        // No exception bubbles up
    }

    @Test
    void previewReturnsEmailAndExpiry() throws Exception {
        var token = "raw-token";
        var entity = new PasswordResetTokenEntity();
        entity.setId(UUID.randomUUID());
        entity.setUserId(userId);
        entity.setOrganizationId(orgId);
        entity.setStatus(PasswordResetStatusType.PENDING);
        entity.setExpiresAt(Instant.now().plusSeconds(600));
        when(repository.findByTokenHash(sha256Hex(token))).thenReturn(Optional.of(entity));
        when(userQueryService.findById(userId)).thenReturn(Optional.of(eligibleUser()));

        var preview = service.previewByToken(token);

        assertThat(preview.email()).isEqualTo("alice@example.com");
        assertThat(preview.expiresAt()).isEqualTo(entity.getExpiresAt());
    }

    @Test
    void previewThrowsForBlankToken() {
        assertThatThrownBy(() -> service.previewByToken(""))
                .isInstanceOf(PasswordResetTokenNotFoundException.class);
        assertThatThrownBy(() -> service.previewByToken(null))
                .isInstanceOf(PasswordResetTokenNotFoundException.class);
    }

    @Test
    void previewThrowsWhenTokenMissing() throws Exception {
        when(repository.findByTokenHash(sha256Hex("raw"))).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.previewByToken("raw"))
                .isInstanceOf(PasswordResetTokenNotFoundException.class);
    }

    @Test
    void previewThrowsWhenTokenUsed() throws Exception {
        var entity = new PasswordResetTokenEntity();
        entity.setStatus(PasswordResetStatusType.USED);
        entity.setExpiresAt(Instant.now().plusSeconds(600));
        when(repository.findByTokenHash(sha256Hex("raw"))).thenReturn(Optional.of(entity));

        assertThatThrownBy(() -> service.previewByToken("raw"))
                .isInstanceOf(PasswordResetTokenAlreadyUsedException.class);
    }

    @Test
    void previewThrowsWhenTokenRevoked() throws Exception {
        var entity = new PasswordResetTokenEntity();
        entity.setStatus(PasswordResetStatusType.REVOKED);
        entity.setExpiresAt(Instant.now().plusSeconds(600));
        when(repository.findByTokenHash(sha256Hex("raw"))).thenReturn(Optional.of(entity));

        assertThatThrownBy(() -> service.previewByToken("raw"))
                .isInstanceOf(PasswordResetTokenRevokedException.class);
    }

    @Test
    void previewThrowsWhenTokenAlreadyExpiredStatus() throws Exception {
        var entity = new PasswordResetTokenEntity();
        entity.setStatus(PasswordResetStatusType.EXPIRED);
        entity.setExpiresAt(Instant.now().plusSeconds(600));
        when(repository.findByTokenHash(sha256Hex("raw"))).thenReturn(Optional.of(entity));

        assertThatThrownBy(() -> service.previewByToken("raw"))
                .isInstanceOf(PasswordResetTokenExpiredException.class);
    }

    @Test
    void previewMarksExpiredWhenPastTtl() throws Exception {
        var entity = new PasswordResetTokenEntity();
        entity.setId(UUID.randomUUID());
        entity.setStatus(PasswordResetStatusType.PENDING);
        entity.setExpiresAt(Instant.now().minusSeconds(10));
        when(repository.findByTokenHash(sha256Hex("raw"))).thenReturn(Optional.of(entity));

        assertThatThrownBy(() -> service.previewByToken("raw"))
                .isInstanceOf(PasswordResetTokenExpiredException.class);

        assertThat(entity.getStatus()).isEqualTo(PasswordResetStatusType.EXPIRED);
        verify(repository).save(entity);
    }

    @Test
    void previewThrowsWhenUserGone() throws Exception {
        var entity = new PasswordResetTokenEntity();
        entity.setUserId(userId);
        entity.setStatus(PasswordResetStatusType.PENDING);
        entity.setExpiresAt(Instant.now().plusSeconds(600));
        when(repository.findByTokenHash(sha256Hex("raw"))).thenReturn(Optional.of(entity));
        when(userQueryService.findById(userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.previewByToken("raw"))
                .isInstanceOf(PasswordResetTokenNotFoundException.class);
    }

    @Test
    void resetPasswordSetsStatusUsedAndDelegates() throws Exception {
        var entity = new PasswordResetTokenEntity();
        entity.setId(UUID.randomUUID());
        entity.setUserId(userId);
        entity.setOrganizationId(orgId);
        entity.setStatus(PasswordResetStatusType.PENDING);
        entity.setExpiresAt(Instant.now().plusSeconds(600));
        when(repository.findByTokenHash(sha256Hex("raw"))).thenReturn(Optional.of(entity));

        var returned = service.resetPassword("raw", "NewPass123!");

        assertThat(returned).isEqualTo(userId);
        assertThat(entity.getStatus()).isEqualTo(PasswordResetStatusType.USED);
        assertThat(entity.getUsedAt()).isNotNull();
        verify(userProfileService).resetPassword(userId, "NewPass123!");
        verify(repository).save(entity);
    }

    @Test
    void resetPasswordRejectsShortPassword() {
        assertThatThrownBy(() -> service.resetPassword("raw", "short"))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(repository, userProfileService);
    }

    @Test
    void resetPasswordRejectsTooLongPassword() {
        var tooLong = "a".repeat(129);
        assertThatThrownBy(() -> service.resetPassword("raw", tooLong))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(repository, userProfileService);
    }

    @Test
    void resetPasswordRejectsNullPassword() {
        assertThatThrownBy(() -> service.resetPassword("raw", null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
