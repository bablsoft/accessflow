package com.bablsoft.accessflow.apigov.internal.client;

import com.bablsoft.accessflow.apigov.api.ApiAuthMethod;
import com.bablsoft.accessflow.apigov.api.ApiExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Computes the HTTP auth headers for an outbound call from the connector's {@link ApiAuthMethod} and
 * decrypted credential map. Header-based methods are computed locally; OAuth2 client-credentials
 * fetches a token from the configured token endpoint. mTLS (client-certificate) auth is not yet wired
 * into execution and is treated as no auth headers (documented limitation).
 */
@Component
public class ApiConnectorAuthApplier {

    private static final Logger log = LoggerFactory.getLogger(ApiConnectorAuthApplier.class);

    private final ObjectMapper objectMapper;

    public ApiConnectorAuthApplier(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

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
            case OAUTH2_CLIENT_CREDENTIALS ->
                    headers.put("Authorization", "Bearer " + fetchClientCredentialsToken(credentials, timeoutMs));
            case MTLS -> log.warn("mTLS execution auth is not yet wired; sending without client cert");
            default -> { /* NONE handled above */ }
        }
        return headers;
    }

    private String fetchClientCredentialsToken(Map<String, String> credentials, int timeoutMs) {
        var tokenUrl = credentials.get("token-url");
        if (tokenUrl == null || tokenUrl.isBlank()) {
            throw new ApiExecutionException("OAuth2 client-credentials connector is missing token-url");
        }
        var form = "grant_type=client_credentials"
                + "&client_id=" + enc(credentials.getOrDefault("client-id", ""))
                + "&client_secret=" + enc(credentials.getOrDefault("client-secret", ""))
                + (credentials.containsKey("scopes") ? "&scope=" + enc(credentials.get("scopes")) : "");
        try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(timeoutMs)).build()) {
            var request = HttpRequest.newBuilder(URI.create(tokenUrl))
                    .timeout(Duration.ofMillis(timeoutMs))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(form))
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new ApiExecutionException("OAuth2 token endpoint returned HTTP " + response.statusCode());
            }
            var token = objectMapper.readTree(response.body()).path("access_token").asString();
            if (token == null || token.isBlank()) {
                throw new ApiExecutionException("OAuth2 token endpoint did not return an access_token");
            }
            return token;
        } catch (java.io.IOException ex) {
            throw new ApiExecutionException("OAuth2 token fetch failed: " + ex.getMessage());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new ApiExecutionException("OAuth2 token fetch interrupted");
        }
    }

    private static String enc(String value) {
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
