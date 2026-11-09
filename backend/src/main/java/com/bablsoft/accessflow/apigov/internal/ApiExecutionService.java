package com.bablsoft.accessflow.apigov.internal;

import com.bablsoft.accessflow.apigov.api.ApiAuthMethod;
import com.bablsoft.accessflow.apigov.api.ApiBodyType;
import com.bablsoft.accessflow.apigov.api.ApiConnectorQuotaExceededException;
import com.bablsoft.accessflow.apigov.api.ApiConnectorMaskingResolutionService;
import com.bablsoft.accessflow.apigov.api.ApiConnectorVariableResolutionService;
import com.bablsoft.accessflow.apigov.api.ApiExecutionException;
import com.bablsoft.accessflow.apigov.api.ApiFormField;
import com.bablsoft.accessflow.apigov.api.ApiInlineExecutionService;
import com.bablsoft.accessflow.apigov.api.ApiVariableRequestContext;
import com.bablsoft.accessflow.apigov.api.IllegalApiRequestStateException;
import com.bablsoft.accessflow.apigov.api.ResolvedApiMask;
import com.bablsoft.accessflow.apigov.api.ResolvedApiVariables;
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
import com.bablsoft.accessflow.audit.api.AuditAction;
import com.bablsoft.accessflow.audit.api.AuditEntry;
import com.bablsoft.accessflow.audit.api.AuditLogService;
import com.bablsoft.accessflow.audit.api.AuditResourceType;
import com.bablsoft.accessflow.core.api.CredentialEncryptionService;
import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.core.api.SubmissionReason;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
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
 * Every non-break-glass execution first passes the connector rate limits (#972); a throttled request
 * stays APPROVED with {@code throttled_until} stamped and is retried by the run job.
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
    private final ApiConnectorVariableResolutionService variableResolutionService;
    private final ApigovRequestProperties requestProperties;
    private final ApiConnectorRateLimiter rateLimiter;
    private final AuditLogService auditLogService;
    private final Clock clock;

    /** What started an execution — selects who writes the outcome audit row and its trigger. */
    public enum ExecutionTrigger {
        /** The synchronous execute endpoint or break-glass; the caller audits the outcome. */
        EXECUTE("execute"),
        /** {@code ApiRequestRunJob} firing a request at its {@code scheduled_for}. */
        SCHEDULED("scheduled"),
        /** {@code ApiRequestRunJob} retrying a request once its {@code throttled_until} passed. */
        QUOTA_RETRY("quota_retry");

        private final String auditValue;

        ExecutionTrigger(String auditValue) {
            this.auditValue = auditValue;
        }

        String auditValue() {
            return auditValue;
        }
    }

    // noRollbackFor: a throttled request's throttled_until / throttle_count stamp and its first
    // API_QUOTA_EXCEEDED audit row must survive the exception that reports the 429.
    @Transactional(noRollbackFor = ApiConnectorQuotaExceededException.class)
    public ApiRequestEntity execute(UUID apiRequestId) {
        return execute(apiRequestId, ExecutionTrigger.EXECUTE);
    }

    @Transactional(noRollbackFor = ApiConnectorQuotaExceededException.class)
    public ApiRequestEntity execute(UUID apiRequestId, ExecutionTrigger trigger) {
        var request = stateService.require(apiRequestId);
        if (request.getStatus() != QueryStatus.APPROVED) {
            throw new IllegalApiRequestStateException(request.getStatus(),
                    "API request must be APPROVED to execute");
        }
        var connector = connectorRepository.findById(request.getConnectorId())
                .orElseThrow(() -> new ApiExecutionException("Connector no longer exists"));
        if (request.getSubmissionReason() != SubmissionReason.EMERGENCY_ACCESS
                && rateLimiter.tryAcquire(connector, request.getSubmittedBy())
                        instanceof ApiConnectorRateLimiter.Throttled throttled) {
            throw defer(request, throttled, trigger);
        }
        // Past the limiter: the deferral is over (throttle_count stays as history).
        request.setThrottledUntil(null);
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
            auditSystemOutcome(AuditAction.API_REQUEST_EXECUTED, request, trigger);
            return request;
        } catch (ApiExecutionException ex) {
            log.warn("API request {} execution failed: {}", request.getId(), ex.getMessage());
            request.setErrorMessage(ex.getMessage());
            stateService.apply(request, QueryStatus.FAILED);
            eventPublisher.publishEvent(new ApiRequestDecidedEvent(request.getId(), QueryStatus.FAILED,
                    ex.getMessage()));
            auditSystemOutcome(AuditAction.API_REQUEST_FAILED, request, trigger);
            return request;
        }
    }

    private ApiConnectorQuotaExceededException defer(ApiRequestEntity request,
                                                     ApiConnectorRateLimiter.Throttled throttled,
                                                     ExecutionTrigger trigger) {
        var retryAfter = Math.max(1, throttled.retryAfterSeconds());
        var throttledUntil = clock.instant().plusSeconds(retryAfter);
        boolean first = request.getThrottleCount() == 0;
        request.setThrottledUntil(throttledUntil);
        request.setThrottleCount(request.getThrottleCount() + 1);
        requestRepository.save(request);
        if (first) {
            var metadata = new HashMap<String, Object>();
            metadata.put("connector_id", request.getConnectorId().toString());
            metadata.put("limit_kind", throttled.kind().name());
            metadata.put("retry_at", throttledUntil.toString());
            metadata.put("trigger", trigger.auditValue());
            var actor = trigger == ExecutionTrigger.EXECUTE ? request.getSubmittedBy() : null;
            audit(AuditAction.API_QUOTA_EXCEEDED, request, actor, metadata);
        } else {
            log.debug("API request {} throttled again ({} limit), retry at {}", request.getId(),
                    throttled.kind(), throttledUntil);
        }
        return new ApiConnectorQuotaExceededException(request.getConnectorId(), throttled.kind(),
                retryAfter, throttledUntil);
    }

    /**
     * The run job has no caller to audit for it, so a scheduled / quota-retry run writes its own
     * outcome row ({@code trigger} names the mechanism). The submitter is the actor — the
     * {@code DefaultQueryLifecycleService} scheduled-run precedent — because grant-usage intelligence
     * attributes {@code API_REQUEST_EXECUTED} rows by actor, and a null actor would make a request
     * that ran after a 429 never count as use of the submitter's grant.
     */
    private void auditSystemOutcome(AuditAction action, ApiRequestEntity request, ExecutionTrigger trigger) {
        if (trigger == ExecutionTrigger.EXECUTE) {
            return;
        }
        var metadata = new HashMap<String, Object>();
        metadata.put("connector_id", request.getConnectorId().toString());
        metadata.put("path", request.getRequestPath());
        metadata.put("status", request.getStatus().name());
        metadata.put("trigger", trigger.auditValue());
        if (request.getOperationId() != null && !request.getOperationId().isBlank()) {
            metadata.put("operation_id", request.getOperationId());
        }
        audit(action, request, request.getSubmittedBy(), metadata);
    }

    private void audit(AuditAction action, ApiRequestEntity request, UUID actorId, Map<String, Object> metadata) {
        try {
            auditLogService.record(new AuditEntry(action, AuditResourceType.API_REQUEST, request.getId(),
                    request.getOrganizationId(), actorId, metadata, null, null));
        } catch (RuntimeException ex) {
            // Audit must never fail the execution flow.
            log.warn("Failed to audit {} for API request {}: {}", action, request.getId(), ex.getMessage());
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
        // A request-group member has no retry loop: a throttled member is reported as a failed one.
        // A break-glass group skips the limiter, like a break-glass request.
        if (!command.breakGlass() && rateLimiter.tryAcquire(connector, command.userId())
                instanceof ApiConnectorRateLimiter.Throttled throttled) {
            var message = "Connector " + throttled.kind() + " rate limit exceeded; retry after "
                    + throttled.retryAfterSeconds() + "s";
            log.debug("Inline API call to connector {} throttled: {}", connector.getId(), message);
            return new ApiInlineExecutionResult(false, 0, null, null, false, null, null, message);
        }
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
        var raw = new ApiCallRequest(connector.getProtocol(), connector.getBaseUrl(),
                request.getVerb(), request.getRequestPath(), headers, readMap(request.getQueryParams()),
                request.getBodyType() == null ? ApiBodyType.RAW : request.getBodyType(),
                request.getRequestBody(), request.getRequestContentType(), readFormFields(request.getFormFields()),
                request.getBinaryFilename(), connector.getTimeoutMs(), responseCap,
                request.getOperationId());

        // AF-613. Variables resolve here rather than earlier because `headers` is already final at
        // this point — connector defaults, per-request headers, trace headers and the auth header the
        // applier just computed — so an expression may sign the finished Authorization value, which
        // is what the motivating vendor scheme requires. The context deliberately describes the
        // pre-substitution request: a body being signed must still contain its {{signature}}
        // placeholder when the digest is taken.
        var context = new ApiVariableRequestContext(raw.verb(), raw.path(),
                ApiRequestVariableSubstitution.canonicalQuery(raw.queryParams()),
                ApiRequestVariableSubstitution.bodyForContext(raw), raw.headers());
        var resolved = variableResolutionService.resolve(connector.getOrganizationId(),
                connector.getId(), context, readMap(request.getVariableOverrides()));
        var substituted = ApiRequestVariableSubstitution.apply(raw, resolved);

        try {
            return executor.execute(substituted);
        } catch (ApiExecutionException ex) {
            // The upstream failure message can embed the substituted URI (the JDK's IOException does
            // exactly that), and api_requests.error_message is persisted and shown to reviewers. Scrub
            // every resolved value before it can escape — the resolver cannot tell a harmless
            // signature from a CONSTANT holding a shared secret, so all of them are redacted.
            throw new ApiExecutionException(redact(ex.getMessage(), resolved));
        }
    }

    /** Replaces every resolved variable value in {@code message} with {@code ***}. */
    static String redact(String message, ResolvedApiVariables resolved) {
        if (message == null || resolved == null) {
            return message;
        }
        var scrubbed = message;
        for (var value : resolved.secretValues()) {
            if (value != null && !value.isBlank()) {
                scrubbed = scrubbed.replace(value, "***");
            }
        }
        return scrubbed;
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
