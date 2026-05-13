package com.bablsoft.accessflow.security.internal;

import com.bablsoft.accessflow.core.api.AuthProviderType;
import com.bablsoft.accessflow.core.api.CreateUserCommand;
import com.bablsoft.accessflow.core.api.OrganizationLookupService;
import com.bablsoft.accessflow.core.api.PageRequest;
import com.bablsoft.accessflow.core.api.SystemSmtpSendingConfig;
import com.bablsoft.accessflow.core.api.SystemSmtpService;
import com.bablsoft.accessflow.core.api.UserAdminService;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.core.api.UserView;
import com.bablsoft.accessflow.security.api.DuplicatePendingInvitationException;
import com.bablsoft.accessflow.security.api.InvitationAlreadyAcceptedException;
import com.bablsoft.accessflow.security.api.InvitationExpiredException;
import com.bablsoft.accessflow.security.api.InvitationNotFoundException;
import com.bablsoft.accessflow.security.api.InvitationRevokedException;
import com.bablsoft.accessflow.security.api.InviteUserCommand;
import com.bablsoft.accessflow.security.api.SystemSmtpNotConfiguredForInviteException;
import com.bablsoft.accessflow.security.api.UserInvitationStatusType;
import com.bablsoft.accessflow.security.internal.config.InvitationProperties;
import com.bablsoft.accessflow.security.internal.persistence.entity.UserInvitationEntity;
import com.bablsoft.accessflow.security.internal.persistence.repo.UserInvitationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.thymeleaf.spring6.SpringTemplateEngine;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultUserInvitationServiceTest {

    @Mock UserInvitationRepository repository;
    @Mock SystemSmtpService systemSmtpService;
    @Mock UserAdminService userAdminService;
    @Mock OrganizationLookupService organizationLookupService;
    @Mock PasswordEncoder passwordEncoder;
    @Mock SpringTemplateEngine templateEngine;

    private DefaultUserInvitationService service;

    private final UUID orgId = UUID.randomUUID();
    private final UUID invitedBy = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        var props = new InvitationProperties(Duration.ofDays(7), URI.create("https://app.example.test"));
        service = new DefaultUserInvitationService(repository, systemSmtpService, userAdminService,
                organizationLookupService, passwordEncoder, templateEngine, props);
    }

    private void smtpConfigured() {
        when(systemSmtpService.resolveSendingConfig(orgId)).thenReturn(Optional.of(
                new SystemSmtpSendingConfig(orgId, "h", 587, "u", "p", true, "f@x.com", "F")));
    }

    private void smtpNotConfigured() {
        when(systemSmtpService.resolveSendingConfig(orgId)).thenReturn(Optional.empty());
    }

    @Test
    void invitePersistsRecordAndSendsEmail() {
        smtpConfigured();
        when(repository.existsByOrganizationIdAndEmailIgnoreCaseAndStatus(eq(orgId), anyString(),
                eq(UserInvitationStatusType.PENDING))).thenReturn(false);
        when(repository.save(any(UserInvitationEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(organizationLookupService.findNameById(orgId)).thenReturn(Optional.of("Acme"));
        when(templateEngine.process(anyString(), any())).thenReturn("<html/>");

        var result = service.invite(new InviteUserCommand("alice@example.com", "Alice",
                UserRoleType.ANALYST), orgId, invitedBy);

        assertThat(result.invitation().email()).isEqualTo("alice@example.com");
        assertThat(result.invitation().status()).isEqualTo(UserInvitationStatusType.PENDING);
        assertThat(result.plaintextToken()).isNotBlank();
        verify(systemSmtpService).sendSystemEmail(eq(orgId), eq("alice@example.com"),
                anyString(), anyString());
    }

    @Test
    void inviteRejectsDuplicatePendingForSameEmail() {
        smtpConfigured();
        when(repository.existsByOrganizationIdAndEmailIgnoreCaseAndStatus(eq(orgId), anyString(),
                eq(UserInvitationStatusType.PENDING))).thenReturn(true);

        assertThatThrownBy(() -> service.invite(
                new InviteUserCommand("alice@example.com", null, UserRoleType.ANALYST),
                orgId, invitedBy))
                .isInstanceOf(DuplicatePendingInvitationException.class);
        verify(repository, never()).save(any());
    }

    @Test
    void inviteFailsWhenSmtpNotConfigured() {
        smtpNotConfigured();

        assertThatThrownBy(() -> service.invite(
                new InviteUserCommand("alice@example.com", null, UserRoleType.ANALYST),
                orgId, invitedBy))
                .isInstanceOf(SystemSmtpNotConfiguredForInviteException.class);
        verify(repository, never()).save(any());
    }

    @Test
    void revokeMarksInvitationRevoked() {
        var entity = pendingInvitation("alice@example.com");
        when(repository.findByIdAndOrganizationId(entity.getId(), orgId)).thenReturn(Optional.of(entity));
        when(repository.save(any(UserInvitationEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        service.revoke(entity.getId(), orgId);

        assertThat(entity.getStatus()).isEqualTo(UserInvitationStatusType.REVOKED);
        assertThat(entity.getRevokedAt()).isNotNull();
    }

    @Test
    void revokeFailsWhenAlreadyAccepted() {
        var entity = pendingInvitation("alice@example.com");
        entity.setStatus(UserInvitationStatusType.ACCEPTED);
        when(repository.findByIdAndOrganizationId(entity.getId(), orgId)).thenReturn(Optional.of(entity));

        assertThatThrownBy(() -> service.revoke(entity.getId(), orgId))
                .isInstanceOf(InvitationAlreadyAcceptedException.class);
    }

    @Test
    void revokeFailsWhenNotFound() {
        var id = UUID.randomUUID();
        when(repository.findByIdAndOrganizationId(id, orgId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.revoke(id, orgId))
                .isInstanceOf(InvitationNotFoundException.class);
    }

    @Test
    void resendRotatesTokenAndExtendsExpiry() {
        var entity = pendingInvitation("alice@example.com");
        var originalHash = entity.getTokenHash();
        var originalExpiry = entity.getExpiresAt();
        when(repository.findByIdAndOrganizationId(entity.getId(), orgId)).thenReturn(Optional.of(entity));
        when(repository.save(any(UserInvitationEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        smtpConfigured();
        when(organizationLookupService.findNameById(orgId)).thenReturn(Optional.of("Acme"));
        when(templateEngine.process(anyString(), any())).thenReturn("<html/>");

        var issued = service.resend(entity.getId(), orgId, invitedBy);

        assertThat(issued.plaintextToken()).isNotBlank();
        assertThat(entity.getTokenHash()).isNotEqualTo(originalHash);
        assertThat(entity.getExpiresAt()).isAfter(originalExpiry);
        assertThat(entity.getStatus()).isEqualTo(UserInvitationStatusType.PENDING);
    }

    @Test
    void resendFailsWhenRevoked() {
        var entity = pendingInvitation("alice@example.com");
        entity.setStatus(UserInvitationStatusType.REVOKED);
        when(repository.findByIdAndOrganizationId(entity.getId(), orgId)).thenReturn(Optional.of(entity));

        assertThatThrownBy(() -> service.resend(entity.getId(), orgId, invitedBy))
                .isInstanceOf(InvitationRevokedException.class);
    }

    @Test
    void previewLooksUpByTokenHash() {
        var token = "raw-token";
        var entity = pendingInvitation("alice@example.com");
        entity.setTokenHash(sha256Hex(token));
        when(repository.findByTokenHash(entity.getTokenHash())).thenReturn(Optional.of(entity));
        when(organizationLookupService.findNameById(orgId)).thenReturn(Optional.of("Acme"));

        var preview = service.previewByToken(token);

        assertThat(preview.email()).isEqualTo("alice@example.com");
        assertThat(preview.organizationName()).isEqualTo("Acme");
    }

    @Test
    void previewExpiredMarksEntityExpired() {
        var token = "raw-token";
        var entity = pendingInvitation("alice@example.com");
        entity.setTokenHash(sha256Hex(token));
        entity.setExpiresAt(Instant.now().minusSeconds(60));
        when(repository.findByTokenHash(entity.getTokenHash())).thenReturn(Optional.of(entity));
        when(repository.save(any(UserInvitationEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        assertThatThrownBy(() -> service.previewByToken(token))
                .isInstanceOf(InvitationExpiredException.class);
        assertThat(entity.getStatus()).isEqualTo(UserInvitationStatusType.EXPIRED);
    }

    @Test
    void acceptCreatesUserAndMarksAccepted() {
        var token = "raw-token";
        var entity = pendingInvitation("alice@example.com");
        entity.setTokenHash(sha256Hex(token));
        when(repository.findByTokenHash(entity.getTokenHash())).thenReturn(Optional.of(entity));
        when(repository.save(any(UserInvitationEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(passwordEncoder.encode("password1")).thenReturn("ENCODED");
        var newUserId = UUID.randomUUID();
        when(userAdminService.createUser(any(CreateUserCommand.class))).thenReturn(
                new UserView(newUserId, "alice@example.com", "Alice",
                        UserRoleType.ANALYST, orgId, true, AuthProviderType.LOCAL, "ENCODED",
                        null, null, false, null));

        var accepted = service.acceptInvitation(token, "password1", "Override");

        assertThat(accepted.userId()).isEqualTo(newUserId);
        assertThat(accepted.organizationId()).isEqualTo(orgId);
        assertThat(entity.getStatus()).isEqualTo(UserInvitationStatusType.ACCEPTED);
    }

    @Test
    void acceptFailsForShortPassword() {
        assertThatThrownBy(() -> service.acceptInvitation("token", "short", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void acceptFailsForUnknownToken() {
        when(repository.findByTokenHash(anyString())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.acceptInvitation("token", "password1", null))
                .isInstanceOf(InvitationNotFoundException.class);
    }

    @Test
    void acceptFailsWhenAlreadyAccepted() {
        var token = "raw-token";
        var entity = pendingInvitation("alice@example.com");
        entity.setTokenHash(sha256Hex(token));
        entity.setStatus(UserInvitationStatusType.ACCEPTED);
        when(repository.findByTokenHash(entity.getTokenHash())).thenReturn(Optional.of(entity));

        assertThatThrownBy(() -> service.acceptInvitation(token, "password1", null))
                .isInstanceOf(InvitationAlreadyAcceptedException.class);
    }

    @Test
    void listReturnsMappedPage() {
        var entity = pendingInvitation("alice@example.com");
        Page<UserInvitationEntity> page = new PageImpl<>(List.of(entity));
        when(repository.findAllByOrganizationId(eq(orgId), any(Pageable.class))).thenReturn(page);

        var result = service.list(orgId, PageRequest.of(0, 20));

        assertThat(result.content()).hasSize(1);
        assertThat(result.content().get(0).email()).isEqualTo("alice@example.com");
    }

    private UserInvitationEntity pendingInvitation(String email) {
        var entity = new UserInvitationEntity();
        entity.setId(UUID.randomUUID());
        entity.setOrganizationId(orgId);
        entity.setEmail(email);
        entity.setRole(UserRoleType.ANALYST);
        entity.setStatus(UserInvitationStatusType.PENDING);
        entity.setTokenHash("placeholder");
        entity.setExpiresAt(Instant.now().plus(Duration.ofDays(1)));
        entity.setCreatedAt(Instant.now());
        entity.setInvitedByUserId(invitedBy);
        return entity;
    }

    private static String sha256Hex(String input) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
