package com.bablsoft.accessflow.security.internal;

import com.bablsoft.accessflow.core.api.CredentialEncryptionService;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.security.api.OAuth2ConfigInvalidException;
import com.bablsoft.accessflow.security.api.OAuth2ConfigService;
import com.bablsoft.accessflow.security.api.OAuth2ConfigView;
import com.bablsoft.accessflow.security.api.OAuth2ProviderSummaryView;
import com.bablsoft.accessflow.security.api.OAuth2ProviderType;
import com.bablsoft.accessflow.security.api.UpdateOAuth2ConfigCommand;
import com.bablsoft.accessflow.security.internal.oauth2.OAuth2ConfigDeletedEvent;
import com.bablsoft.accessflow.security.internal.oauth2.OAuth2ConfigUpdatedEvent;
import com.bablsoft.accessflow.security.internal.persistence.entity.OAuth2ConfigEntity;
import com.bablsoft.accessflow.security.internal.persistence.repo.OAuth2ConfigRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
class DefaultOAuth2ConfigService implements OAuth2ConfigService {

    private final OAuth2ConfigRepository repository;
    private final CredentialEncryptionService encryptionService;
    private final ApplicationEventPublisher eventPublisher;
    private final MessageSource messageSource;

    @Override
    @Transactional(readOnly = true)
    public List<OAuth2ConfigView> list(UUID organizationId) {
        var stored = repository.findAllByOrganizationId(organizationId);
        var byProvider = new java.util.EnumMap<OAuth2ProviderType, OAuth2ConfigEntity>(OAuth2ProviderType.class);
        for (var row : stored) {
            byProvider.put(row.getProvider(), row);
        }
        var out = new ArrayList<OAuth2ConfigView>();
        for (var provider : OAuth2ProviderType.values()) {
            var entity = byProvider.get(provider);
            out.add(entity != null ? toView(entity) : defaultView(organizationId, provider));
        }
        return out;
    }

    @Override
    @Transactional(readOnly = true)
    public OAuth2ConfigView getOrDefault(UUID organizationId, OAuth2ProviderType provider) {
        return repository.findByOrganizationIdAndProvider(organizationId, provider)
                .map(this::toView)
                .orElseGet(() -> defaultView(organizationId, provider));
    }

    @Override
    @Transactional
    public OAuth2ConfigView update(UUID organizationId, OAuth2ProviderType provider,
                                   UpdateOAuth2ConfigCommand command) {
        var existing = repository.findByOrganizationIdAndProvider(organizationId, provider)
                .orElse(null);
        var entity = existing != null ? existing : seed(organizationId, provider);

        if (command.clientId() != null) {
            var trimmed = command.clientId().trim();
            if (!trimmed.isEmpty()) {
                entity.setClientId(trimmed);
            }
        }
        applyClientSecret(entity, command.clientSecret());
        if (command.scopesOverride() != null) {
            entity.setScopesOverride(blankToNull(command.scopesOverride()));
        }
        if (command.tenantId() != null) {
            entity.setTenantId(blankToNull(command.tenantId()));
        }
        if (command.defaultRole() != null) {
            entity.setDefaultRole(command.defaultRole());
        }
        if (command.active() != null) {
            entity.setActive(command.active());
        }

        if (entity.isActive()) {
            if (entity.getClientId() == null || entity.getClientId().isBlank()) {
                throw new OAuth2ConfigInvalidException(messageSource.getMessage(
                        "error.oauth2.client_id_required_to_activate", null,
                        LocaleContextHolder.getLocale()));
            }
            if (entity.getClientSecretEncrypted() == null
                    || entity.getClientSecretEncrypted().isBlank()) {
                throw new OAuth2ConfigInvalidException(messageSource.getMessage(
                        "error.oauth2.client_secret_required_to_activate", null,
                        LocaleContextHolder.getLocale()));
            }
            if (provider == OAuth2ProviderType.MICROSOFT
                    && (entity.getTenantId() == null || entity.getTenantId().isBlank())) {
                throw new OAuth2ConfigInvalidException(messageSource.getMessage(
                        "error.oauth2.tenant_id_required_for_microsoft", null,
                        LocaleContextHolder.getLocale()));
            }
        }

        entity.setUpdatedAt(Instant.now());
        var saved = repository.save(entity);
        eventPublisher.publishEvent(new OAuth2ConfigUpdatedEvent(
                saved.getOrganizationId(), saved.getProvider(), saved.isActive()));
        return toView(saved);
    }

    @Override
    @Transactional
    public void delete(UUID organizationId, OAuth2ProviderType provider) {
        repository.deleteByOrganizationIdAndProvider(organizationId, provider);
        eventPublisher.publishEvent(new OAuth2ConfigDeletedEvent(organizationId, provider));
    }

    @Override
    @Transactional(readOnly = true)
    public List<OAuth2ProviderSummaryView> listActive(UUID organizationId) {
        return repository.findAllByOrganizationIdAndActiveTrue(organizationId).stream()
                .map(e -> new OAuth2ProviderSummaryView(e.getProvider(), displayName(e.getProvider())))
                .toList();
    }

    private void applyClientSecret(OAuth2ConfigEntity entity, String submitted) {
        if (submitted == null || UpdateOAuth2ConfigCommand.MASKED_SECRET.equals(submitted)) {
            return;
        }
        if (submitted.isBlank()) {
            entity.setClientSecretEncrypted(null);
            entity.setActive(false);
            return;
        }
        entity.setClientSecretEncrypted(encryptionService.encrypt(submitted));
    }

    private OAuth2ConfigEntity seed(UUID organizationId, OAuth2ProviderType provider) {
        var entity = new OAuth2ConfigEntity();
        entity.setId(UUID.randomUUID());
        entity.setOrganizationId(organizationId);
        entity.setProvider(provider);
        return entity;
    }

    private OAuth2ConfigView defaultView(UUID organizationId, OAuth2ProviderType provider) {
        var now = Instant.now();
        return new OAuth2ConfigView(
                null,
                organizationId,
                provider,
                null,
                false,
                null,
                null,
                UserRoleType.ANALYST,
                false,
                now,
                now);
    }

    private OAuth2ConfigView toView(OAuth2ConfigEntity entity) {
        return new OAuth2ConfigView(
                entity.getId(),
                entity.getOrganizationId(),
                entity.getProvider(),
                entity.getClientId(),
                entity.getClientSecretEncrypted() != null
                        && !entity.getClientSecretEncrypted().isBlank(),
                entity.getScopesOverride(),
                entity.getTenantId(),
                entity.getDefaultRole(),
                entity.isActive(),
                entity.getCreatedAt(),
                entity.getUpdatedAt());
    }

    private static String displayName(OAuth2ProviderType provider) {
        return switch (provider) {
            case GOOGLE -> "Google";
            case GITHUB -> "GitHub";
            case MICROSOFT -> "Microsoft";
            case GITLAB -> "GitLab";
        };
    }

    private static String blankToNull(String s) {
        if (s == null) return null;
        var trimmed = s.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
