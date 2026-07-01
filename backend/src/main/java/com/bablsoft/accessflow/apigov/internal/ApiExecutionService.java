package com.bablsoft.accessflow.apigov.internal;

import com.bablsoft.accessflow.apigov.api.ApiAuthMethod;
import com.bablsoft.accessflow.apigov.api.ApiBodyType;
import com.bablsoft.accessflow.apigov.api.ApiConnectorMaskingResolutionService;
import com.bablsoft.accessflow.apigov.api.ApiExecutionException;
import com.bablsoft.accessflow.apigov.api.ApiFormField;
import com.bablsoft.accessflow.apigov.api.ApiInlineExecutionService;
import com.bablsoft.accessflow.apigov.api.IllegalApiRequestStateException;
import com.bablsoft.accessflow.apigov.api.ResolvedApiMask;
import com.bablsoft.accessflow.apigov.events.ApiRequestDecidedEvent;
import com.bablsoft.accessflow.apigov.internal.client.ApiCallExecutor;
import com.bablsoft.accessflow.apigov.internal.client.ApiCallRequest;
import com.bablsoft.accessflow.apigov.internal.client.ApiCallResult;
import com.bablsoft.accessflow.apigov.internal.client.ApiConnectorAuthApplier;
import com.bablsoft.accessflow.apigov.internal.config.ApigovRequestProperties;
import com.bablsoft.accessflow.apigov.internal.persistence.entity.ApiConnectorEntity;
import com.bablsoft.accessflow.apigov.internal.persistence.entity.ApiRequestEntity;
import com.bablsoft.accessflow.apigov.internal.persistence.repo.ApiConnectorRepository;
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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Executes an APPROVED API request against the upstream target: injects connector auth + default
 * headers, caps the response, masks the caller's {@code restricted_response_fields} recursively by
 * dot-path, stores an immutable (masked) response snapshot, and records the EXECUTED / FAILED
 * outcome. Used by the submitter-triggered execute endpoint, the scheduled-run job, and break-glass.
 */
@Service
@RequiredArgsConstructor
public class ApiExecutionService implements ApiInlineExecutionService {

    private static final Logger log = LoggerFactory.getLogger(ApiExecutionService.class);
    private static final TypeReference<Map<String, String>> MAP_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<List<ApiFormField>> FORM_FIELDS_TYPE = new TypeReference<>() {
    };

    private final ApiRequestRepository requestRepository;
    private final ApiConnectorRepository connectorRepository;
    private final EffectiveApiConnectorPermissionResolver permissionResolver;
    private final CredentialEncryptionService encryptionService;
    private final ApiConnectorAuthApplier authApplier;
    private final ConnectorOAuth2TokenService oauth2TokenService;
    private final ApiCallExecutor executor;
    private final ApiResponseMasker responseMasker;
    private final ApiConnectorMaskingResolutionService maskingResolutionService;
    private final ApiRequestStateService stateService;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;
    private final ApigovRequestProperties requestProperties;

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
            var masks = resolveMasks(connector, request.getSubmittedBy());
            var masked = responseMasker.mask(result.body(), result.contentType(), masks);
            request.setAppliedMaskingPolicyIds(appliedPolicyIds(masks));
            request.setResponseStatusCode(result.statusCode());
            request.setResponseDurationMs(result.durationMs());
            request.setResponseBytes(result.bytes());
            request.setResponseTruncated(result.truncated());
            request.setResponseSnapshot(masked);
            request.setResponseContentType(result.contentType());
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

    @Override
    public ApiInlineExecutionResult executeInline(ApiInlineExecutionCommand command) {
        var connector = connectorRepository
                .findByIdAndOrganizationId(command.connectorId(), command.organizationId())
                .orElseThrow(() -> new ApiExecutionException("Connector no longer exists"));
        // A detached (never-persisted) request entity reuses the full invoke() plumbing — connector
        // auth, default + per-call headers, response cap — without touching the api_requests table.
        var transient_ = new ApiRequestEntity();
        transient_.setConnectorId(connector.getId());
        transient_.setOrganizationId(command.organizationId());
        transient_.setSubmittedBy(command.userId());
        transient_.setOperationId(command.operationId());
        transient_.setVerb(command.verb());
        transient_.setRequestPath(command.requestPath());
        transient_.setRequestHeaders(blankToJsonObject(command.requestHeadersJson()));
        transient_.setQueryParams(blankToJsonObject(command.queryParamsJson()));
        transient_.setBodyType(command.bodyType() == null ? ApiBodyType.RAW : command.bodyType());
        transient_.setRequestContentType(command.requestContentType());
        transient_.setRequestBody(command.requestBody());
        transient_.setFormFields(command.formFieldsJson() == null || command.formFieldsJson().isBlank()
                ? "[]" : command.formFieldsJson());
        transient_.setBinaryFilename(command.binaryFilename());
        try {
            var result = invoke(connector, transient_);
            var masked = responseMasker.mask(result.body(), result.contentType(),
                    resolveMasks(connector, command.userId()));
            return new ApiInlineExecutionResult(result.statusCode() < 400, result.statusCode(),
                    result.durationMs(), result.bytes(), result.truncated(), masked,
                    result.contentType(), null);
        } catch (ApiExecutionException ex) {
            log.warn("Inline API call to connector {} failed: {}", connector.getId(), ex.getMessage());
            return new ApiInlineExecutionResult(false, 0, null, null, false, null, null, ex.getMessage());
        }
    }

    private static String blankToJsonObject(String json) {
        return json == null || json.isBlank() ? "{}" : json;
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
        applyTraceHeaders(headers, connector, request);
        if (bearerToken != null) {
            headers.put("Authorization", "Bearer " + bearerToken);
        } else {
            headers.putAll(authApplier.authHeaders(connector.getAuthMethod(),
                    decryptCredentials(connector), connector.getTimeoutMs()));
        }
        return headers;
    }

    private void applyTraceHeaders(Map<String, String> headers, ApiConnectorEntity connector,
                                   ApiRequestEntity request) {
        if (request.getTraceId() == null || request.getSpanId() == null) {
            return;
        }
        var mapping = readMap(connector.getTraceHeaderMapping());
        var traceparentHeader = mapping.getOrDefault("traceparent", "traceparent");
        if (traceparentHeader != null && !traceparentHeader.isBlank()) {
            headers.put(traceparentHeader, TraceContext.traceparent(request.getTraceId(), request.getSpanId()));
        }
    }

    private ApiCallResult executeCall(ApiConnectorEntity connector, ApiRequestEntity request,
                                      Map<String, String> headers) {
        var responseCap = Math.min(connector.getMaxResponseBytes(), requestProperties.maxResponseBytes());
        return executor.execute(new ApiCallRequest(connector.getProtocol(), connector.getBaseUrl(),
                request.getVerb(), request.getRequestPath(), headers, readMap(request.getQueryParams()),
                request.getBodyType() == null ? ApiBodyType.RAW : request.getBodyType(),
                request.getRequestBody(), request.getRequestContentType(), readFormFields(request.getFormFields()),
                request.getBinaryFilename(), connector.getTimeoutMs(), responseCap,
                request.getOperationId()));
    }

    private List<ApiFormField> readFormFields(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, FORM_FIELDS_TYPE);
        } catch (RuntimeException ex) {
            return List.of();
        }
    }

    private Map<String, String> decryptCredentials(ApiConnectorEntity connector) {
        var enc = connector.getAuthCredentialsEncrypted();
        if (enc == null || enc.isBlank()) {
            return Map.of();
        }
        return readMap(encryptionService.decrypt(enc));
    }

    /**
     * Resolves the masks applied to this caller's response: connector-level masking policies (AF-518)
     * merged with the caller's legacy per-permission {@code restricted_response_fields} (FULL masks,
     * kept for back-compat). Masked once; the raw body never persists.
     */
    private List<ResolvedApiMask> resolveMasks(ApiConnectorEntity connector, UUID userId) {
        var masks = new ArrayList<>(maskingResolutionService.resolveApplicable(
                connector.getOrganizationId(), connector.getId(), userId));
        for (var field : restrictedFields(connector.getId(), userId)) {
            masks.add(ResolvedApiMask.legacyRestrictedField(field));
        }
        return masks;
    }

    private static List<UUID> appliedPolicyIds(List<ResolvedApiMask> masks) {
        return masks.stream().map(ResolvedApiMask::policyId).filter(Objects::nonNull).distinct().toList();
    }

    private List<String> restrictedFields(UUID connectorId, UUID userId) {
        // Effective restricted fields = intersection across the user's direct + group grants (AF-530).
        return permissionResolver.resolve(connectorId, userId)
                .map(EffectiveApiConnectorPermissionResolver.ResolvedApiConnectorPermission::restrictedResponseFields)
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
