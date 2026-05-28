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
import java.util.Locale;
import java.util.UUID;

@Service
@RequiredArgsConstructor
class DefaultOAuth2ConfigService implements OAuth2ConfigService {

    static final String GITHUB_READ_ORG_SCOPE = "read:org";

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
        if (command.displayName() != null) {
            entity.setDisplayName(blankToNull(command.displayName()));
        }
        if (command.authorizationUri() != null) {
            entity.setAuthorizationUri(blankToNull(command.authorizationUri()));
        }
        if (command.tokenUri() != null) {
            entity.setTokenUri(blankToNull(command.tokenUri()));
        }
        if (command.userInfoUri() != null) {
            entity.setUserInfoUri(blankToNull(command.userInfoUri()));
        }
        if (command.jwkSetUri() != null) {
            entity.setJwkSetUri(blankToNull(command.jwkSetUri()));
        }
        if (command.issuerUri() != null) {
            entity.setIssuerUri(blankToNull(command.issuerUri()));
        }
        if (command.userNameAttribute() != null) {
            entity.setUserNameAttribute(blankToNull(command.userNameAttribute()));
        }
        if (command.emailAttribute() != null) {
            entity.setEmailAttribute(blankToNull(command.emailAttribute()));
        }
        if (command.emailVerifiedAttribute() != null) {
            entity.setEmailVerifiedAttribute(blankToNull(command.emailVerifiedAttribute()));
        }
        if (command.displayNameAttribute() != null) {
            entity.setDisplayNameAttribute(blankToNull(command.displayNameAttribute()));
        }
        if (command.groupsAttribute() != null) {
            entity.setGroupsAttribute(blankToNull(command.groupsAttribute()));
        }
        if (command.baseUrl() != null) {
            entity.setBaseUrl(blankToNull(command.baseUrl()));
        }
        if (command.allowedOrganizations() != null) {
            entity.setAllowedOrganizations(normalizeOrganizations(command.allowedOrganizations()));
        }
        if (command.allowedEmailDomains() != null) {
            entity.setAllowedEmailDomains(normalizeEmailDomains(command.allowedEmailDomains()));
        }
        if (command.groupMappings() != null) {
            entity.setGroupMappings(new java.util.HashMap<>(command.groupMappings()));
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
            if ((provider == OAuth2ProviderType.GITHUB
                            || provider == OAuth2ProviderType.GITHUB_ENTERPRISE)
                    && hasEntries(entity.getAllowedOrganizations())
                    && !scopesContain(entity.getScopesOverride(), GITHUB_READ_ORG_SCOPE)) {
                throw new OAuth2ConfigInvalidException(messageSource.getMessage(
                        "error.oauth2.github_read_org_scope_required", null,
                        LocaleContextHolder.getLocale()));
            }
            if (provider == OAuth2ProviderType.GITHUB_ENTERPRISE
                    || provider == OAuth2ProviderType.GITLAB_ENTERPRISE) {
                validateEnterpriseBaseUrl(entity);
            }
            if (provider == OAuth2ProviderType.OIDC) {
                validateOidcEntity(entity);
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
                .map(e -> new OAuth2ProviderSummaryView(e.getProvider(), displayNameFor(e)))
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
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                List.of(),
                List.of(),
                java.util.Map.of(),
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
                entity.getDisplayName(),
                entity.getAuthorizationUri(),
                entity.getTokenUri(),
                entity.getUserInfoUri(),
                entity.getJwkSetUri(),
                entity.getIssuerUri(),
                entity.getUserNameAttribute(),
                entity.getEmailAttribute(),
                entity.getEmailVerifiedAttribute(),
                entity.getDisplayNameAttribute(),
                entity.getGroupsAttribute(),
                entity.getBaseUrl(),
                toList(entity.getAllowedOrganizations()),
                toList(entity.getAllowedEmailDomains()),
                entity.getGroupMappings() == null ? java.util.Map.of()
                        : java.util.Map.copyOf(entity.getGroupMappings()),
                entity.getDefaultRole(),
                entity.isActive(),
                entity.getCreatedAt(),
                entity.getUpdatedAt());
    }

    private static String displayNameFor(OAuth2ConfigEntity entity) {
        var override = entity.getDisplayName();
        var trimmed = override == null ? null : override.trim();
        if (trimmed != null && !trimmed.isEmpty()) {
            if (entity.getProvider() == OAuth2ProviderType.OIDC
                    || entity.getProvider() == OAuth2ProviderType.GITHUB_ENTERPRISE
                    || entity.getProvider() == OAuth2ProviderType.GITLAB_ENTERPRISE) {
                return trimmed;
            }
        }
        return switch (entity.getProvider()) {
            case GOOGLE -> "Google";
            case GITHUB -> "GitHub";
            case MICROSOFT -> "Microsoft";
            case GITLAB -> "GitLab";
            case OIDC -> "OpenID Connect";
            case GITHUB_ENTERPRISE -> "GitHub Enterprise";
            case GITLAB_ENTERPRISE -> "GitLab (self-managed)";
        };
    }

    private void validateEnterpriseBaseUrl(OAuth2ConfigEntity entity) {
        var baseUrl = entity.getBaseUrl();
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new OAuth2ConfigInvalidException(messageSource.getMessage(
                    "error.oauth2.base_url_required_for_enterprise", null,
                    LocaleContextHolder.getLocale()));
        }
        java.net.URI uri;
        try {
            uri = java.net.URI.create(baseUrl.trim());
        } catch (IllegalArgumentException ex) {
            throw new OAuth2ConfigInvalidException(messageSource.getMessage(
                    "error.oauth2.base_url_invalid", new Object[] {baseUrl},
                    LocaleContextHolder.getLocale()));
        }
        var scheme = uri.getScheme();
        if (scheme == null || !scheme.equalsIgnoreCase("https") || uri.getHost() == null) {
            throw new OAuth2ConfigInvalidException(messageSource.getMessage(
                    "error.oauth2.base_url_invalid", new Object[] {baseUrl},
                    LocaleContextHolder.getLocale()));
        }
        var path = uri.getPath();
        if (path != null && !path.isEmpty() && !path.equals("/")) {
            throw new OAuth2ConfigInvalidException(messageSource.getMessage(
                    "error.oauth2.base_url_must_be_origin", new Object[] {baseUrl},
                    LocaleContextHolder.getLocale()));
        }
        if (uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw new OAuth2ConfigInvalidException(messageSource.getMessage(
                    "error.oauth2.base_url_must_be_origin", new Object[] {baseUrl},
                    LocaleContextHolder.getLocale()));
        }
    }

    private void validateOidcEntity(OAuth2ConfigEntity entity) {
        requireOidcField(entity.getDisplayName(), "error.oauth2.oidc_display_name_required");
        requireOidcUri(entity.getAuthorizationUri(), "error.oauth2.oidc_authorization_uri_required");
        requireOidcUri(entity.getTokenUri(), "error.oauth2.oidc_token_uri_required");
        requireOidcUri(entity.getUserInfoUri(), "error.oauth2.oidc_user_info_uri_required");
        requireOidcUri(entity.getJwkSetUri(), "error.oauth2.oidc_jwk_set_uri_required");
        requireOidcUri(entity.getIssuerUri(), "error.oauth2.oidc_issuer_uri_required");
    }

    private void requireOidcField(String value, String missingKey) {
        if (value == null || value.isBlank()) {
            throw new OAuth2ConfigInvalidException(messageSource.getMessage(
                    missingKey, null, LocaleContextHolder.getLocale()));
        }
    }

    private void requireOidcUri(String value, String missingKey) {
        requireOidcField(value, missingKey);
        java.net.URI uri;
        try {
            uri = java.net.URI.create(value.trim());
        } catch (IllegalArgumentException ex) {
            throw new OAuth2ConfigInvalidException(messageSource.getMessage(
                    "error.oauth2.oidc_uri_invalid", new Object[] {value},
                    LocaleContextHolder.getLocale()));
        }
        var scheme = uri.getScheme();
        if (scheme == null
                || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))
                || uri.getHost() == null) {
            throw new OAuth2ConfigInvalidException(messageSource.getMessage(
                    "error.oauth2.oidc_uri_invalid", new Object[] {value},
                    LocaleContextHolder.getLocale()));
        }
    }

    private static String blankToNull(String s) {
        if (s == null) return null;
        var trimmed = s.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String[] normalizeOrganizations(List<String> values) {
        return values.stream()
                .filter(java.util.Objects::nonNull)
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .distinct()
                .toArray(String[]::new);
    }

    private static String[] normalizeEmailDomains(List<String> values) {
        return values.stream()
                .filter(java.util.Objects::nonNull)
                .map(s -> s.trim().toLowerCase(Locale.ROOT))
                .filter(s -> !s.isEmpty())
                .distinct()
                .toArray(String[]::new);
    }

    private static boolean hasEntries(String[] arr) {
        return arr != null && arr.length > 0;
    }

    private static boolean scopesContain(String scopes, String token) {
        if (scopes == null || scopes.isBlank()) return false;
        for (var part : scopes.split("\\s+")) {
            if (part.equals(token)) return true;
        }
        return false;
    }

    private static List<String> toList(String[] arr) {
        return arr == null ? List.of() : List.of(arr);
    }
}
