package com.bablsoft.accessflow.security.internal;

import com.bablsoft.accessflow.core.api.AuthProviderType;
import com.bablsoft.accessflow.core.api.OrganizationLookupService;
import com.bablsoft.accessflow.core.api.SystemSmtpNotConfiguredException;
import com.bablsoft.accessflow.core.api.SystemSmtpService;
import com.bablsoft.accessflow.core.api.UserProfileService;
import com.bablsoft.accessflow.core.api.UserQueryService;
import com.bablsoft.accessflow.core.api.UserView;
import com.bablsoft.accessflow.security.api.PasswordResetPreview;
import com.bablsoft.accessflow.security.api.PasswordResetService;
import com.bablsoft.accessflow.security.api.PasswordResetStatusType;
import com.bablsoft.accessflow.security.api.PasswordResetTokenAlreadyUsedException;
import com.bablsoft.accessflow.security.api.PasswordResetTokenExpiredException;
import com.bablsoft.accessflow.security.api.PasswordResetTokenNotFoundException;
import com.bablsoft.accessflow.security.api.PasswordResetTokenRevokedException;
import com.bablsoft.accessflow.security.internal.config.PasswordResetProperties;
import com.bablsoft.accessflow.security.internal.persistence.entity.PasswordResetTokenEntity;
import com.bablsoft.accessflow.security.internal.persistence.repo.PasswordResetTokenRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
class DefaultPasswordResetService implements PasswordResetService {

    private static final int TOKEN_BYTES = 32;
    private static final int MIN_PASSWORD_LENGTH = 8;
    private static final int MAX_PASSWORD_LENGTH = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final PasswordResetTokenRepository repository;
    private final UserQueryService userQueryService;
    private final UserProfileService userProfileService;
    private final SystemSmtpService systemSmtpService;
    private final OrganizationLookupService organizationLookupService;
    private final SpringTemplateEngine templateEngine;
    private final PasswordResetProperties properties;
    private final MessageSource messageSource;

    @Override
    @Transactional
    public void requestReset(String email) {
        if (email == null || email.isBlank()) {
            return;
        }
        var normalized = email.trim();
        var user = userQueryService.findByEmail(normalized).orElse(null);
        if (!isEligible(user)) {
            return;
        }
        if (systemSmtpService.resolveSendingConfig(user.organizationId()).isEmpty()) {
            log.warn("System SMTP not configured for org {}; password reset email skipped for user {}",
                    user.organizationId(), user.id());
            return;
        }
        repository.findFirstByUserIdAndStatus(user.id(), PasswordResetStatusType.PENDING)
                .ifPresent(existing -> {
                    existing.setStatus(PasswordResetStatusType.REVOKED);
                    existing.setRevokedAt(Instant.now());
                    repository.saveAndFlush(existing);
                });
        var token = generateToken();
        var entity = new PasswordResetTokenEntity();
        entity.setId(UUID.randomUUID());
        entity.setUserId(user.id());
        entity.setOrganizationId(user.organizationId());
        entity.setTokenHash(sha256Hex(token));
        entity.setStatus(PasswordResetStatusType.PENDING);
        entity.setExpiresAt(Instant.now().plus(properties.ttl()));
        entity.setCreatedAt(Instant.now());
        try {
            repository.saveAndFlush(entity);
        } catch (DataIntegrityViolationException ex) {
            log.warn("Concurrent password-reset request for user {}; swallowing", user.id());
            return;
        }
        sendResetEmail(user, token, entity.getExpiresAt());
    }

    @Override
    @Transactional(readOnly = true)
    public PasswordResetPreview previewByToken(String plaintextToken) {
        var entity = lookupValid(plaintextToken);
        var user = userQueryService.findById(entity.getUserId())
                .orElseThrow(PasswordResetTokenNotFoundException::new);
        return new PasswordResetPreview(user.email(), entity.getExpiresAt());
    }

    @Override
    @Transactional
    public UUID resetPassword(String plaintextToken, String newPassword) {
        validatePasswordLength(newPassword);
        var entity = lookupValid(plaintextToken);
        userProfileService.resetPassword(entity.getUserId(), newPassword);
        entity.setStatus(PasswordResetStatusType.USED);
        entity.setUsedAt(Instant.now());
        repository.save(entity);
        return entity.getUserId();
    }

    private boolean isEligible(UserView user) {
        if (user == null) {
            return false;
        }
        if (user.authProvider() != AuthProviderType.LOCAL) {
            return false;
        }
        if (!user.active()) {
            return false;
        }
        return user.passwordHash() != null && !user.passwordHash().isBlank();
    }

    private PasswordResetTokenEntity lookupValid(String plaintextToken) {
        if (plaintextToken == null || plaintextToken.isBlank()) {
            throw new PasswordResetTokenNotFoundException();
        }
        var entity = repository.findByTokenHash(sha256Hex(plaintextToken))
                .orElseThrow(PasswordResetTokenNotFoundException::new);
        return validateAcceptable(entity);
    }

    private PasswordResetTokenEntity validateAcceptable(PasswordResetTokenEntity entity) {
        switch (entity.getStatus()) {
            case USED -> throw new PasswordResetTokenAlreadyUsedException();
            case REVOKED -> throw new PasswordResetTokenRevokedException();
            case EXPIRED -> throw new PasswordResetTokenExpiredException();
            default -> { /* PENDING falls through */ }
        }
        if (entity.getExpiresAt().isBefore(Instant.now())) {
            entity.setStatus(PasswordResetStatusType.EXPIRED);
            repository.save(entity);
            throw new PasswordResetTokenExpiredException();
        }
        return entity;
    }

    private void validatePasswordLength(String password) {
        if (password == null || password.length() < MIN_PASSWORD_LENGTH
                || password.length() > MAX_PASSWORD_LENGTH) {
            var msg = messageSource.getMessage(
                    "validation.new_password.size",
                    null,
                    "New password must be between 8 and 128 characters",
                    LocaleContextHolder.getLocale());
            throw new IllegalArgumentException(msg);
        }
    }

    private void sendResetEmail(UserView user, String plaintextToken, Instant expiresAt) {
        var orgName = organizationLookupService.findNameById(user.organizationId()).orElse("");
        var resetUrl = buildResetUrl(plaintextToken);
        var subject = "[AccessFlow] Reset your password";
        var html = renderTemplate(user.email(), orgName, resetUrl, expiresAt);
        try {
            systemSmtpService.sendSystemEmail(user.organizationId(), user.email(), subject, html);
        } catch (SystemSmtpNotConfiguredException ex) {
            log.warn("System SMTP missing for org {} during password reset send", user.organizationId());
        } catch (RuntimeException ex) {
            log.error("Failed to send password reset email for user {}", user.id(), ex);
        }
    }

    private String renderTemplate(String recipientEmail, String organizationName, String resetUrl,
                                  Instant expiresAt) {
        var ctx = new Context(Locale.US);
        ctx.setVariable("organizationName", organizationName);
        ctx.setVariable("recipientEmail", recipientEmail);
        ctx.setVariable("resetUrl", resetUrl);
        ctx.setVariable("expiresAt", expiresAt);
        return templateEngine.process("email/password-reset", ctx);
    }

    private String buildResetUrl(String plaintextToken) {
        var base = properties.resetBaseUrl().toString();
        var trimmed = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        return trimmed + "/reset-password/" + plaintextToken;
    }

    private static String generateToken() {
        var bytes = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String sha256Hex(String input) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            var hashed = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashed);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 missing", ex);
        }
    }
}
