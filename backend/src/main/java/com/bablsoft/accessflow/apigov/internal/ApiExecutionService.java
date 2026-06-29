package com.bablsoft.accessflow.apigov.internal;

import com.bablsoft.accessflow.apigov.api.ApiAuthMethod;
import com.bablsoft.accessflow.apigov.api.ApiExecutionException;
import com.bablsoft.accessflow.apigov.api.IllegalApiRequestStateException;
import com.bablsoft.accessflow.apigov.events.ApiRequestDecidedEvent;
import com.bablsoft.accessflow.apigov.internal.client.ApiCallExecutor;
import com.bablsoft.accessflow.apigov.internal.client.ApiCallResult;
import com.bablsoft.accessflow.apigov.internal.client.ApiConnectorAuthApplier;
import com.bablsoft.accessflow.apigov.internal.persistence.entity.ApiConnectorEntity;
import com.bablsoft.accessflow.apigov.internal.persistence.entity.ApiRequestEntity;
import com.bablsoft.accessflow.apigov.internal.persistence.repo.ApiConnectorRepository;
import com.bablsoft.accessflow.apigov.internal.persistence.repo.ApiConnectorUserPermissionRepository;
import com.bablsoft.accessflow.apigov.internal.persistence.repo.ApiRequestRepository;
import com.bablsoft.accessflow.core.api.CredentialEncryptionService;
import com.bablsoft.accessflow.core.api.QueryStatus;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Executes an APPROVED API request against the upstream target: injects connector auth + default
 * headers, caps the response, masks the caller's {@code restricted_response_fields} recursively by
 * dot-path, stores an immutable (masked) response snapshot, and records the EXECUTED / FAILED
 * outcome. Used by the submitter-triggered execute endpoint, the scheduled-run job, and break-glass.
 */
@Service
@RequiredArgsConstructor
public class ApiExecutionService {

    private static final Logger log = LoggerFactory.getLogger(ApiExecutionService.class);
    private static final TypeReference<Map<String, String>> MAP_TYPE = new TypeReference<>() {
    };

    private final ApiRequestRepository requestRepository;
    private final ApiConnectorRepository connectorRepository;
    private final ApiConnectorUserPermissionRepository permissionRepository;
    private final CredentialEncryptionService encryptionService;
    private final ApiConnectorAuthApplier authApplier;
    private final ConnectorOAuth2TokenService oauth2TokenService;
    private final ApiCallExecutor executor;
    private final ApiResponseMasker responseMasker;
    private final ApiRequestStateService stateService;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;

    @Transactional
    public ApiRequestEntity execute(UUID apiRequestId) {
        var request = stateService.require(apiRequestId);
        if (request.getStatus() != QueryStatus.APPROVED) {
            throw new IllegalApiRequestStateException(request.getStatus(),
                    "API request must be APPROVED to execute");
        }
        var connector = connectorRepository.findById(request.getConnectorId())
                .orElseThrow(() -> new ApiExecutionException("Connector no longer exists"));
        try {
            var result = invoke(connector, request);
            var masked = responseMasker.mask(result.body(), restrictedFields(connector.getId(), request.getSubmittedBy()));
            request.setResponseStatusCode(result.statusCode());
            request.setResponseDurationMs(result.durationMs());
            request.setResponseBytes(result.bytes());
            request.setResponseTruncated(result.truncated());
            request.setResponseSnapshot(masked);
            request.setErrorMessage(null);
            stateService.apply(request, QueryStatus.EXECUTED);
            eventPublisher.publishEvent(new ApiRequestDecidedEvent(request.getId(), QueryStatus.EXECUTED, null));
            return request;
        } catch (ApiExecutionException ex) {
            log.warn("API request {} execution failed: {}", request.getId(), ex.getMessage());
            request.setErrorMessage(ex.getMessage());
            stateService.apply(request, QueryStatus.FAILED);
            eventPublisher.publishEvent(new ApiRequestDecidedEvent(request.getId(), QueryStatus.FAILED,
                    ex.getMessage()));
            return request;
        }
    }

    private ApiCallResult invoke(ApiConnectorEntity connector, ApiRequestEntity request) {
        var oauth2 = connector.getAuthMethod() == ApiAuthMethod.OAUTH2_CLIENT_CREDENTIALS;
        var bearer = oauth2 ? oauth2TokenService.accessToken(connector) : null;
        var result = executeCall(connector, request, buildHeaders(connector, request, bearer));
        // Outbound 401 on an OAuth2 connector likely means a stale cached token — evict, refresh, and
        // retry exactly once. Static-credential methods surface a 401 as-is (it's a real auth error).
        if (oauth2 && result.statusCode() == 401) {
            oauth2TokenService.evict(connector.getId());
            var refreshed = oauth2TokenService.fetchFresh(connector);
            return executeCall(connector, request, buildHeaders(connector, request, refreshed));
        }
        return result;
    }

    private Map<String, String> buildHeaders(ApiConnectorEntity connector, ApiRequestEntity request,
                                             String bearerToken) {
        var headers = new LinkedHashMap<String, String>();
        headers.putAll(readMap(connector.getDefaultHeaders()));
        headers.putAll(readMap(request.getRequestHeaders()));
        if (bearerToken != null) {
            headers.put("Authorization", "Bearer " + bearerToken);
        } else {
            headers.putAll(authApplier.authHeaders(connector.getAuthMethod(),
                    decryptCredentials(connector), connector.getTimeoutMs()));
        }
        return headers;
    }

    private ApiCallResult executeCall(ApiConnectorEntity connector, ApiRequestEntity request,
                                      Map<String, String> headers) {
        return executor.execute(connector.getProtocol(), connector.getBaseUrl(), request.getVerb(),
                request.getRequestPath(), headers, request.getRequestBody(), connector.getTimeoutMs(),
                connector.getMaxResponseBytes(), request.getOperationId());
    }

    private Map<String, String> decryptCredentials(ApiConnectorEntity connector) {
        var enc = connector.getAuthCredentialsEncrypted();
        if (enc == null || enc.isBlank()) {
            return Map.of();
        }
        return readMap(encryptionService.decrypt(enc));
    }

    private List<String> restrictedFields(UUID connectorId, UUID userId) {
        return permissionRepository.findByConnectorIdAndUserId(connectorId, userId)
                .map(p -> p.getRestrictedResponseFields() == null ? List.<String>of()
                        : List.of(p.getRestrictedResponseFields()))
                .orElseGet(List::of);
    }

    private Map<String, String> readMap(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, MAP_TYPE);
        } catch (RuntimeException ex) {
            return Map.of();
        }
    }
}
