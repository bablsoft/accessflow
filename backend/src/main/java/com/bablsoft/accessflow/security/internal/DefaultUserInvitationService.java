package com.bablsoft.accessflow.security.internal;

import com.bablsoft.accessflow.core.api.CreateUserCommand;
import com.bablsoft.accessflow.core.api.OrganizationLookupService;
import com.bablsoft.accessflow.core.api.PageRequest;
import com.bablsoft.accessflow.core.api.PageResponse;
import com.bablsoft.accessflow.core.api.SortOrder;
import com.bablsoft.accessflow.core.api.SystemSmtpNotConfiguredException;
import com.bablsoft.accessflow.core.api.SystemSmtpService;
import com.bablsoft.accessflow.core.api.UserAdminService;
import com.bablsoft.accessflow.security.api.AcceptedInvitation;
import com.bablsoft.accessflow.security.api.DuplicatePendingInvitationException;
import com.bablsoft.accessflow.security.api.InvitationAlreadyAcceptedException;
import com.bablsoft.accessflow.security.api.InvitationExpiredException;
import com.bablsoft.accessflow.security.api.InvitationNotFoundException;
import com.bablsoft.accessflow.security.api.InvitationPreview;
import com.bablsoft.accessflow.security.api.InvitationRevokedException;
import com.bablsoft.accessflow.security.api.InviteUserCommand;
import com.bablsoft.accessflow.security.api.IssuedInvitation;
import com.bablsoft.accessflow.security.api.SystemSmtpNotConfiguredForInviteException;
import com.bablsoft.accessflow.security.api.UserInvitationService;
import com.bablsoft.accessflow.security.api.UserInvitationStatusType;
import com.bablsoft.accessflow.security.api.UserInvitationView;
import com.bablsoft.accessflow.security.internal.config.InvitationProperties;
import com.bablsoft.accessflow.security.internal.persistence.entity.UserInvitationEntity;
import com.bablsoft.accessflow.security.internal.persistence.repo.UserInvitationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Sort;
import org.springframework.data.domain.Sort.Direction;
import org.springframework.security.crypto.password.PasswordEncoder;
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
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
class DefaultUserInvitationService implements UserInvitationService {

    private static final int TOKEN_BYTES = 32;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserInvitationRepository repository;
    private final SystemSmtpService systemSmtpService;
    private final UserAdminService userAdminService;
    private final OrganizationLookupService organizationLookupService;
    private final PasswordEncoder passwordEncoder;
    private final SpringTemplateEngine templateEngine;
    private final InvitationProperties properties;

    @Override
    @Transactional
    public IssuedInvitation invite(InviteUserCommand command, UUID organizationId, UUID invitedByUserId) {
        if (command == null || command.email() == null || command.email().isBlank()) {
            throw new IllegalArgumentException("Invitation email is required");
        }
        var normalizedEmail = command.email().trim();
        ensureSystemSmtpReady(organizationId);
        if (repository.existsByOrganizationIdAndEmailIgnoreCaseAndStatus(organizationId,
                normalizedEmail, UserInvitationStatusType.PENDING)) {
            throw new DuplicatePendingInvitationException();
        }
        var token = generateToken();
        var hash = sha256Hex(token);
        var entity = new UserInvitationEntity();
        entity.setId(UUID.randomUUID());
        entity.setOrganizationId(organizationId);
        entity.setEmail(normalizedEmail);
        entity.setRole(command.role());
        entity.setDisplayName(emptyToNull(command.displayName()));
        entity.setTokenHash(hash);
        entity.setStatus(UserInvitationStatusType.PENDING);
        entity.setExpiresAt(Instant.now().plus(properties.ttl()));
        entity.setInvitedByUserId(invitedByUserId);
        entity.setCreatedAt(Instant.now());
        var saved = repository.save(entity);
        sendInvitationEmail(organizationId, saved, token);
        return new IssuedInvitation(toView(saved), token);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<UserInvitationView> list(UUID organizationId, PageRequest pageRequest) {
        var sort = pageRequest.sort().isEmpty()
                ? Sort.by(Direction.DESC, "createdAt")
                : Sort.by(pageRequest.sort().stream().map(this::toSpringOrder).toList());
        var pageable = org.springframework.data.domain.PageRequest.of(
                pageRequest.page(), pageRequest.size(), sort);
        var page = repository.findAllByOrganizationId(organizationId, pageable);
        var content = page.getContent().stream().map(DefaultUserInvitationService::toView).toList();
        return new PageResponse<>(content, page.getNumber(), page.getSize(),
                page.getTotalElements(), page.getTotalPages());
    }

    @Override
    @Transactional
    public void revoke(UUID invitationId, UUID organizationId) {
        var entity = repository.findByIdAndOrganizationId(invitationId, organizationId)
                .orElseThrow(InvitationNotFoundException::new);
        if (entity.getStatus() == UserInvitationStatusType.ACCEPTED) {
            throw new InvitationAlreadyAcceptedException();
        }
        entity.setStatus(UserInvitationStatusType.REVOKED);
        entity.setRevokedAt(Instant.now());
        repository.save(entity);
    }

    @Override
    @Transactional
    public IssuedInvitation resend(UUID invitationId, UUID organizationId, UUID invitedByUserId) {
        var entity = repository.findByIdAndOrganizationId(invitationId, organizationId)
                .orElseThrow(InvitationNotFoundException::new);
        switch (entity.getStatus()) {
            case ACCEPTED -> throw new InvitationAlreadyAcceptedException();
            case REVOKED -> throw new InvitationRevokedException();
            default -> { /* PENDING or EXPIRED → reissue token */ }
        }
        ensureSystemSmtpReady(organizationId);
        var token = generateToken();
        entity.setTokenHash(sha256Hex(token));
        entity.setStatus(UserInvitationStatusType.PENDING);
        entity.setExpiresAt(Instant.now().plus(properties.ttl()));
        entity.setInvitedByUserId(invitedByUserId);
        var saved = repository.save(entity);
        sendInvitationEmail(organizationId, saved, token);
        return new IssuedInvitation(toView(saved), token);
    }

    @Override
    @Transactional(readOnly = true)
    public InvitationPreview previewByToken(String plaintextToken) {
        var entity = lookupAcceptableInvitation(plaintextToken);
        var orgName = organizationLookupService.findNameById(entity.getOrganizationId()).orElse("");
        return new InvitationPreview(
                entity.getEmail(),
                entity.getDisplayName(),
                entity.getRole(),
                orgName,
                entity.getExpiresAt());
    }

    @Override
    @Transactional
    public AcceptedInvitation acceptInvitation(String plaintextToken, String plaintextPassword,
                                               String displayName) {
        if (plaintextPassword == null || plaintextPassword.length() < 8
                || plaintextPassword.length() > 128) {
            throw new IllegalArgumentException("Password must be between 8 and 128 characters");
        }
        var entity = lookupAcceptableInvitation(plaintextToken);
        var resolvedDisplayName = emptyToNull(displayName);
        if (resolvedDisplayName == null) {
            resolvedDisplayName = entity.getDisplayName();
        }
        var created = userAdminService.createUser(new CreateUserCommand(
                entity.getOrganizationId(),
                entity.getEmail(),
                resolvedDisplayName,
                passwordEncoder.encode(plaintextPassword),
                entity.getRole()));
        entity.setStatus(UserInvitationStatusType.ACCEPTED);
        entity.setAcceptedAt(Instant.now());
        repository.save(entity);
        return new AcceptedInvitation(created.id(), entity.getOrganizationId());
    }

    private UserInvitationEntity lookupAcceptableInvitation(String plaintextToken) {
        if (plaintextToken == null || plaintextToken.isBlank()) {
            throw new InvitationNotFoundException();
        }
        var entity = repository.findByTokenHash(sha256Hex(plaintextToken))
                .orElseThrow(InvitationNotFoundException::new);
        return validateAcceptable(entity);
    }

    private UserInvitationEntity validateAcceptable(UserInvitationEntity entity) {
        switch (entity.getStatus()) {
            case ACCEPTED -> throw new InvitationAlreadyAcceptedException();
            case REVOKED -> throw new InvitationRevokedException();
            case EXPIRED -> throw new InvitationExpiredException();
            default -> { /* PENDING falls through */ }
        }
        if (entity.getExpiresAt().isBefore(Instant.now())) {
            entity.setStatus(UserInvitationStatusType.EXPIRED);
            repository.save(entity);
            throw new InvitationExpiredException();
        }
        return entity;
    }

    private void ensureSystemSmtpReady(UUID organizationId) {
        if (systemSmtpService.resolveSendingConfig(organizationId).isEmpty()) {
            throw new SystemSmtpNotConfiguredForInviteException();
        }
    }

    private void sendInvitationEmail(UUID organizationId, UserInvitationEntity invitation, String token) {
        var orgName = organizationLookupService.findNameById(organizationId).orElse("");
        var acceptUrl = buildAcceptUrl(token);
        var subject = "[AccessFlow] You have been invited to " + (orgName.isBlank() ? "AccessFlow" : orgName);
        var html = renderTemplate(invitation, orgName, acceptUrl);
        try {
            systemSmtpService.sendSystemEmail(organizationId, invitation.getEmail(), subject, html);
        } catch (SystemSmtpNotConfiguredException ex) {
            throw new SystemSmtpNotConfiguredForInviteException();
        }
    }

    private String renderTemplate(UserInvitationEntity invitation, String organizationName, String acceptUrl) {
        var ctx = new Context(Locale.US);
        ctx.setVariable("organizationName", organizationName);
        ctx.setVariable("recipientEmail", invitation.getEmail());
        ctx.setVariable("recipientDisplayName", invitation.getDisplayName());
        ctx.setVariable("role", invitation.getRole().name());
        ctx.setVariable("acceptUrl", acceptUrl);
        ctx.setVariable("expiresAt", invitation.getExpiresAt());
        return templateEngine.process("email/user-invitation", ctx);
    }

    private String buildAcceptUrl(String token) {
        var base = properties.acceptBaseUrl().toString();
        var trimmed = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        return trimmed + "/invite/" + token;
    }

    private Sort.Order toSpringOrder(SortOrder order) {
        var dir = order.direction() == SortOrder.Direction.ASC ? Direction.ASC : Direction.DESC;
        return new Sort.Order(dir, order.property());
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

    private static String emptyToNull(String s) {
        if (s == null) {
            return null;
        }
        var trimmed = s.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    static UserInvitationView toView(UserInvitationEntity entity) {
        return new UserInvitationView(
                entity.getId(),
                entity.getOrganizationId(),
                entity.getEmail(),
                entity.getDisplayName(),
                entity.getRole(),
                entity.getStatus(),
                entity.getExpiresAt(),
                entity.getAcceptedAt(),
                entity.getRevokedAt(),
                entity.getInvitedByUserId(),
                entity.getCreatedAt());
    }

    static Optional<UserInvitationStatusType> safeStatus(UserInvitationEntity entity) {
        return entity == null ? Optional.empty() : Optional.ofNullable(entity.getStatus());
    }
}
