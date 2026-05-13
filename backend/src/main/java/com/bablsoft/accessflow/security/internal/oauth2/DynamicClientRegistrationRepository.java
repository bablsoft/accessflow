package com.bablsoft.accessflow.security.internal.oauth2;

import com.bablsoft.accessflow.core.api.CredentialEncryptionService;
import com.bablsoft.accessflow.core.api.OrganizationLookupService;
import com.bablsoft.accessflow.security.api.OAuth2ProviderType;
import com.bablsoft.accessflow.security.internal.persistence.entity.OAuth2ConfigEntity;
import com.bablsoft.accessflow.security.internal.persistence.repo.OAuth2ConfigRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.oidc.IdTokenClaimNames;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Builds Spring Security {@link ClientRegistration}s from {@code oauth2_config} rows on demand.
 * Caches per registration ID (lowercase provider, e.g. {@code "google"}). Mirrors the
 * {@code AiAnalyzerStrategyHolder} eviction pattern: {@link OAuth2ConfigUpdatedEvent} /
 * {@link OAuth2ConfigDeletedEvent} fire after the transaction commits and drop the cached
 * registration so the next authorization request rebuilds against the new state — no restart.
 *
 * <p>Returns {@code null} (per Spring contract) when no active config exists for the requested
 * registrationId; Spring then routes the request through the usual not-found path and our
 * @ControllerAdvice surfaces a clean error to the browser.
 */
@Component
@RequiredArgsConstructor
public class DynamicClientRegistrationRepository implements ClientRegistrationRepository {

    private static final Logger log = LoggerFactory.getLogger(DynamicClientRegistrationRepository.class);
    private static final String REDIRECT_URI_TEMPLATE = "{baseUrl}/api/v1/auth/oauth2/callback/{registrationId}";

    private final OAuth2ConfigRepository configRepository;
    private final CredentialEncryptionService encryptionService;
    private final OrganizationLookupService organizationLookupService;

    private final ConcurrentHashMap<String, ClientRegistration> cache = new ConcurrentHashMap<>();

    @Override
    public ClientRegistration findByRegistrationId(String registrationId) {
        if (registrationId == null || registrationId.isBlank()) {
            return null;
        }
        var key = registrationId.toLowerCase(Locale.ROOT);
        var cached = cache.get(key);
        if (cached != null) {
            return cached;
        }
        var provider = parseProvider(key);
        if (provider == null) {
            return null;
        }
        var organizationId = organizationLookupService.singleOrganization();
        var entity = configRepository
                .findByOrganizationIdAndProvider(organizationId, provider)
                .orElse(null);
        if (entity == null || !entity.isActive()) {
            return null;
        }
        var registration = build(entity);
        cache.put(key, registration);
        log.debug("Built ClientRegistration for {}", key);
        return registration;
    }

    @ApplicationModuleListener
    void onConfigUpdated(OAuth2ConfigUpdatedEvent event) {
        var key = event.provider().name().toLowerCase(Locale.ROOT);
        var removed = cache.remove(key);
        if (removed != null) {
            log.info("Evicted ClientRegistration {}", key);
        }
    }

    @ApplicationModuleListener
    void onConfigDeleted(OAuth2ConfigDeletedEvent event) {
        var key = event.provider().name().toLowerCase(Locale.ROOT);
        var removed = cache.remove(key);
        if (removed != null) {
            log.info("Evicted ClientRegistration {} (deleted)", key);
        }
    }

    private ClientRegistration build(OAuth2ConfigEntity entity) {
        var template = OAuth2ProviderTemplate.forProvider(entity.getProvider());
        var registrationId = entity.getProvider().name().toLowerCase(Locale.ROOT);
        var scopes = parseScopes(entity.getScopesOverride(), template.defaultScopes());
        var tenant = entity.getTenantId();

        var builder = ClientRegistration.withRegistrationId(registrationId)
                .clientId(entity.getClientId())
                .clientSecret(encryptionService.decrypt(entity.getClientSecretEncrypted()))
                .clientAuthenticationMethod(org.springframework.security.oauth2.core.ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri(REDIRECT_URI_TEMPLATE)
                .scope(scopes)
                .authorizationUri(template.authorizationUri(tenant))
                .tokenUri(template.tokenUri(tenant))
                .userInfoUri(template.userInfoUri())
                .userNameAttributeName(template.userNameAttributeName())
                .clientName(template.displayName());

        if (template.isOidc()) {
            var jwk = template.jwkSetUri(tenant);
            if (jwk != null) {
                builder.jwkSetUri(jwk);
            }
            var issuer = template.issuerUri(tenant);
            if (issuer != null) {
                builder.issuerUri(issuer);
                builder.providerConfigurationMetadata(java.util.Map.of(
                        IdTokenClaimNames.ISS, issuer));
            }
        }
        return builder.build();
    }

    private static Set<String> parseScopes(String override, Set<String> defaults) {
        if (override == null || override.isBlank()) {
            return defaults;
        }
        return Set.copyOf(Arrays.stream(override.trim().split("[\\s,]+"))
                .filter(s -> !s.isBlank())
                .toList());
    }

    private static OAuth2ProviderType parseProvider(String registrationId) {
        try {
            return OAuth2ProviderType.valueOf(registrationId.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
