package com.bablsoft.accessflow.apigov.internal;

import com.bablsoft.accessflow.apigov.api.ApiExecutionException;
import com.bablsoft.accessflow.apigov.api.Oauth2ClientAuth;
import com.bablsoft.accessflow.apigov.api.Oauth2GrantType;
import com.bablsoft.accessflow.apigov.events.ApiConnectorTokenFailureEvent;
import com.bablsoft.accessflow.apigov.internal.config.ApigovOAuth2Properties;
import com.bablsoft.accessflow.apigov.internal.persistence.entity.ApiConnectorEntity;
import com.bablsoft.accessflow.audit.api.AuditAction;
import com.bablsoft.accessflow.audit.api.AuditEntry;
import com.bablsoft.accessflow.audit.api.AuditLogService;
import com.bablsoft.accessflow.audit.api.AuditResourceType;
import com.bablsoft.accessflow.core.api.CredentialEncryptionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.StringJoiner;
import java.util.UUID;

/**
 * Sources, caches, and refreshes the outbound OAuth2 access token a connector uses to call its
 * upstream API (AF-500 / #506). Supports the client-credentials, refresh-token, and resource-owner
 * password grants, each with HTTP-Basic or POST-body client authentication.
 *
 * <p>The access token is cached in Redis encrypted (key {@code apigov:oauth2:token:<connectorId>})
 * with a TTL derived from the token response's {@code expires_in} minus a safety skew. Failures are
 * fail-safe: any problem (missing config, non-2xx, transport error, missing {@code access_token})
 * raises {@link ApiExecutionException}, which the execution path turns into a FAILED request with a
 * clear message. Repeated failures (a per-connector consecutive counter crossing the configured
 * threshold) publish {@link ApiConnectorTokenFailureEvent}. The token, client secret, refresh token,
 * and password are never logged, audited, or returned.
 */
@Service
public class ConnectorOAuth2TokenService {

    private static final Logger log = LoggerFactory.getLogger(ConnectorOAuth2TokenService.class);
    private static final String TOKEN_KEY_PREFIX = "apigov:oauth2:token:";
    private static final String FAIL_KEY_PREFIX = "apigov:oauth2:failcount:";
    private static final Duration MIN_TTL = Duration.ofSeconds(10);
    private static final Duration FAIL_COUNTER_WINDOW = Duration.ofDays(1);

    private final StringRedisTemplate redisTemplate;
    private final CredentialEncryptionService encryptionService;
    private final RestClient restClient;
    private final AuditLogService auditLogService;
    private final ApigovOAuth2Properties properties;
    private final ApplicationEventPublisher eventPublisher;
    private final MessageSource messageSource;

    public ConnectorOAuth2TokenService(StringRedisTemplate redisTemplate,
                                       CredentialEncryptionService encryptionService,
                                       @Qualifier("apigovOAuth2RestClient") RestClient restClient,
                                       AuditLogService auditLogService,
                                       ApigovOAuth2Properties properties,
                                       ApplicationEventPublisher eventPublisher,
                                       MessageSource messageSource) {
        this.redisTemplate = redisTemplate;
        this.encryptionService = encryptionService;
        this.restClient = restClient;
        this.auditLogService = auditLogService;
        this.properties = properties;
        this.eventPublisher = eventPublisher;
        this.messageSource = messageSource;
    }

    /** Returns a usable bearer access token for the connector, from cache or a fresh grant. */
    public String accessToken(ApiConnectorEntity connector) {
        var cached = readCachedToken(connector.getId());
        if (cached != null) {
            return cached;
        }
        return fetchFresh(connector);
    }

    /** Forces a fresh grant (skips the cache read); used by the 401-retry and test-connection paths. */
    public String fetchFresh(ApiConnectorEntity connector) {
        try {
            var token = requestToken(connector);
            cacheToken(connector.getId(), token.accessToken(), token.expiresInSeconds());
            resetFailureCounter(connector.getId());
            audit(connector);
            return token.accessToken();
        } catch (ApiExecutionException ex) {
            recordFailure(connector);
            throw ex;
        }
    }

    /** Drops the cached token for this connector (on config change or upstream 401). */
    public void evict(UUID connectorId) {
        redisTemplate.delete(TOKEN_KEY_PREFIX + connectorId);
    }

    private String readCachedToken(UUID connectorId) {
        var stored = redisTemplate.opsForValue().get(TOKEN_KEY_PREFIX + connectorId);
        if (stored == null || stored.isBlank()) {
            return null;
        }
        try {
            return encryptionService.decrypt(stored);
        } catch (RuntimeException ex) {
            log.warn("Discarding undecryptable cached OAuth2 token for connector {}", connectorId);
            evict(connectorId);
            return null;
        }
    }

    private void cacheToken(UUID connectorId, String accessToken, Long expiresInSeconds) {
        var ttl = computeTtl(expiresInSeconds);
        redisTemplate.opsForValue().set(TOKEN_KEY_PREFIX + connectorId,
                encryptionService.encrypt(accessToken), ttl);
    }

    private Duration computeTtl(Long expiresInSeconds) {
        if (expiresInSeconds == null) {
            return properties.oauth2TokenFallbackTtl();
        }
        var ttl = Duration.ofSeconds(expiresInSeconds).minus(properties.oauth2TokenCacheSkew());
        return ttl.compareTo(MIN_TTL) < 0 ? MIN_TTL : ttl;
    }

    private TokenResponse requestToken(ApiConnectorEntity connector) {
        var tokenUri = connector.getOauth2TokenUri();
        if (tokenUri == null || tokenUri.isBlank()) {
            throw fail("error.apigov.oauth2_token_uri_missing");
        }
        var grantType = connector.getOauth2GrantType() == null
                ? Oauth2GrantType.CLIENT_CREDENTIALS : connector.getOauth2GrantType();
        var clientAuth = connector.getOauth2ClientAuth() == null
                ? Oauth2ClientAuth.CLIENT_SECRET_BASIC : connector.getOauth2ClientAuth();

        var form = new LinkedHashMap<String, String>();
        switch (grantType) {
            case CLIENT_CREDENTIALS -> form.put("grant_type", "client_credentials");
            case REFRESH_TOKEN -> {
                form.put("grant_type", "refresh_token");
                form.put("refresh_token", requireSecret(connector.getOauth2RefreshTokenEncrypted(),
                        "error.apigov.oauth2_refresh_token_missing"));
            }
            case PASSWORD -> {
                form.put("grant_type", "password");
                if (connector.getOauth2Username() == null || connector.getOauth2Username().isBlank()) {
                    throw fail("error.apigov.oauth2_username_missing");
                }
                form.put("username", connector.getOauth2Username());
                form.put("password", requireSecret(connector.getOauth2PasswordEncrypted(),
                        "error.apigov.oauth2_password_missing"));
            }
        }
        if (notBlank(connector.getOauth2Scopes())) {
            form.put("scope", connector.getOauth2Scopes());
        }
        if (notBlank(connector.getOauth2Audience())) {
            form.put("audience", connector.getOauth2Audience());
        }

        var clientId = connector.getOauth2ClientId();
        var clientSecret = decryptOrNull(connector.getOauth2ClientSecretEncrypted());
        var basicHeader = clientAuth == Oauth2ClientAuth.CLIENT_SECRET_BASIC
                ? basicAuth(clientId, clientSecret) : null;
        if (clientAuth == Oauth2ClientAuth.CLIENT_SECRET_POST) {
            if (notBlank(clientId)) {
                form.put("client_id", clientId);
            }
            if (notBlank(clientSecret)) {
                form.put("client_secret", clientSecret);
            }
        }

        JsonNode body = postTokenRequest(tokenUri, form, basicHeader);
        var accessToken = body.path("access_token").asString();
        if (accessToken == null || accessToken.isBlank()) {
            throw fail("error.apigov.oauth2_token_no_access_token");
        }
        var expiresNode = body.path("expires_in");
        Long expiresIn = expiresNode.isNumber() ? expiresNode.asLong() : null;
        return new TokenResponse(accessToken, expiresIn);
    }

    private JsonNode postTokenRequest(String tokenUri, Map<String, String> form, String basicHeader) {
        try {
            return restClient.post()
                    .uri(tokenUri)
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_FORM_URLENCODED_VALUE)
                    .headers(h -> {
                        if (basicHeader != null) {
                            h.set(HttpHeaders.AUTHORIZATION, basicHeader);
                        }
                    })
                    .body(encodeForm(form))
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientException ex) {
            log.warn("OAuth2 token fetch failed for {}: {}", tokenUri, ex.getMessage());
            throw fail("error.apigov.oauth2_token_fetch_failed");
        }
    }

    private void audit(ApiConnectorEntity connector) {
        try {
            auditLogService.record(new AuditEntry(AuditAction.API_CONNECTOR_OAUTH2_TOKEN_REFRESHED,
                    AuditResourceType.API_CONNECTOR, connector.getId(), connector.getOrganizationId(),
                    null, Map.of("grant_type", String.valueOf(connector.getOauth2GrantType())),
                    null, null));
        } catch (RuntimeException ignored) {
            // Audit must never fail the token flow.
        }
    }

    private void resetFailureCounter(UUID connectorId) {
        redisTemplate.delete(FAIL_KEY_PREFIX + connectorId);
    }

    private void recordFailure(ApiConnectorEntity connector) {
        try {
            var key = FAIL_KEY_PREFIX + connector.getId();
            Long count = redisTemplate.opsForValue().increment(key);
            redisTemplate.expire(key, FAIL_COUNTER_WINDOW);
            if (count != null && count == properties.oauth2TokenFailureAlertThreshold()) {
                eventPublisher.publishEvent(new ApiConnectorTokenFailureEvent(
                        connector.getId(), connector.getOrganizationId()));
            }
        } catch (RuntimeException ex) {
            log.warn("Failed to record OAuth2 token-failure counter for connector {}",
                    connector.getId());
        }
    }

    private String requireSecret(String encrypted, String missingKey) {
        var value = decryptOrNull(encrypted);
        if (value == null || value.isBlank()) {
            throw fail(missingKey);
        }
        return value;
    }

    private String decryptOrNull(String encrypted) {
        if (encrypted == null || encrypted.isBlank()) {
            return null;
        }
        return encryptionService.decrypt(encrypted);
    }

    private static String basicAuth(String clientId, String clientSecret) {
        var raw = enc(clientId == null ? "" : clientId) + ":" + enc(clientSecret == null ? "" : clientSecret);
        return "Basic " + Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private static String encodeForm(Map<String, String> form) {
        var joiner = new StringJoiner("&");
        form.forEach((k, v) -> joiner.add(enc(k) + "=" + enc(v == null ? "" : v)));
        return joiner.toString();
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private ApiExecutionException fail(String messageKey) {
        return new ApiExecutionException(
                messageSource.getMessage(messageKey, null, LocaleContextHolder.getLocale()));
    }

    private record TokenResponse(String accessToken, Long expiresInSeconds) {
    }
}
