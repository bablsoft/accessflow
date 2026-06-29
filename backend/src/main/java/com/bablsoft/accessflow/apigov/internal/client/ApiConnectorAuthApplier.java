package com.bablsoft.accessflow.apigov.internal.client;

import com.bablsoft.accessflow.apigov.api.ApiAuthMethod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Computes the HTTP auth headers for an outbound call from the connector's {@link ApiAuthMethod} and
 * decrypted credential map, for the static header-based methods (API key, bearer, basic, custom
 * header). OAuth2 ({@code OAUTH2_CLIENT_CREDENTIALS}) is handled separately by
 * {@code ConnectorOAuth2TokenService} in the execution service, which sources/caches/refreshes the
 * token. mTLS (client-certificate) auth is not yet wired into execution and is treated as no auth
 * headers (documented limitation).
 */
@Component
public class ApiConnectorAuthApplier {

    private static final Logger log = LoggerFactory.getLogger(ApiConnectorAuthApplier.class);

    public Map<String, String> authHeaders(ApiAuthMethod method, Map<String, String> credentials,
                                           int timeoutMs) {
        var headers = new LinkedHashMap<String, String>();
        if (method == null || method == ApiAuthMethod.NONE || credentials == null) {
            return headers;
        }
        switch (method) {
            case API_KEY -> {
                var header = credentials.getOrDefault("header-name", "X-API-Key");
                headers.put(header, credentials.getOrDefault("api-key", ""));
            }
            case BEARER_TOKEN -> headers.put("Authorization", "Bearer " + credentials.getOrDefault("token", ""));
            case BASIC -> {
                var raw = credentials.getOrDefault("username", "") + ":" + credentials.getOrDefault("password", "");
                headers.put("Authorization",
                        "Basic " + Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8)));
            }
            case CUSTOM_HEADER -> headers.put(credentials.getOrDefault("header-name", "X-Auth"),
                    credentials.getOrDefault("header-value", ""));
            case MTLS -> log.warn("mTLS execution auth is not yet wired; sending without client cert");
            default -> { /* NONE + OAUTH2_CLIENT_CREDENTIALS handled elsewhere */ }
        }
        return headers;
    }
}
