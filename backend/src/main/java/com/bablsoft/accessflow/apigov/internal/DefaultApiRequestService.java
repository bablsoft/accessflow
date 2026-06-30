package com.bablsoft.accessflow.apigov.internal;

import com.bablsoft.accessflow.apigov.api.ApiBodyType;
import com.bablsoft.accessflow.apigov.api.ApiConnectorNotFoundException;
import com.bablsoft.accessflow.apigov.api.ApiFormField;
import com.bablsoft.accessflow.apigov.api.ApiOperation;
import com.bablsoft.accessflow.apigov.api.ApiProtocol;
import com.bablsoft.accessflow.apigov.api.ApiRequestListFilter;
import com.bablsoft.accessflow.apigov.api.ApiRequestNotFoundException;
import com.bablsoft.accessflow.apigov.api.ApiRequestPermissionException;
import com.bablsoft.accessflow.apigov.api.ApiRequestService;
import com.bablsoft.accessflow.apigov.api.ApiRequestSubmissionResult;
import com.bablsoft.accessflow.apigov.api.ApiRequestValidationException;
import com.bablsoft.accessflow.apigov.api.ApiResponsePayload;
import com.bablsoft.accessflow.apigov.api.ApiReviewDecisionView;
import com.bablsoft.accessflow.apigov.api.ApiRequestView;
import com.bablsoft.accessflow.apigov.api.IllegalApiRequestStateException;
import com.bablsoft.accessflow.apigov.api.SubmitApiRequestCommand;
import com.bablsoft.accessflow.apigov.internal.config.ApigovRequestProperties;
import com.bablsoft.accessflow.apigov.events.ApiRequestSubmittedEvent;
import com.bablsoft.accessflow.apigov.internal.persistence.entity.ApiConnectorEntity;
import com.bablsoft.accessflow.apigov.internal.persistence.entity.ApiConnectorUserPermissionEntity;
import com.bablsoft.accessflow.apigov.internal.persistence.entity.ApiRequestEntity;
import com.bablsoft.accessflow.apigov.internal.persistence.repo.ApiConnectorRepository;
import com.bablsoft.accessflow.apigov.internal.persistence.repo.ApiConnectorUserPermissionRepository;
import com.bablsoft.accessflow.apigov.internal.persistence.repo.ApiRequestRepository;
import com.bablsoft.accessflow.apigov.internal.persistence.repo.ApiReviewDecisionRepository;
import com.bablsoft.accessflow.apigov.api.ApiSchemaService;
import com.bablsoft.accessflow.audit.api.AuditAction;
import com.bablsoft.accessflow.audit.api.AuditEntry;
import com.bablsoft.accessflow.audit.api.AuditLogService;
import com.bablsoft.accessflow.audit.api.AuditResourceType;
import com.bablsoft.accessflow.core.api.AiAnalysisLookupService;
import com.bablsoft.accessflow.core.api.PageRequest;
import com.bablsoft.accessflow.core.api.PageResponse;
import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.core.api.SubmissionReason;
import com.bablsoft.accessflow.core.api.UserQueryService;
import com.bablsoft.accessflow.core.api.UserView;
import com.bablsoft.accessflow.workflow.api.BreakGlassService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class DefaultApiRequestService implements ApiRequestService {

    private static final Set<String> MUTATING_VERBS = Set.of("POST", "PUT", "PATCH", "DELETE");

    private final ApiRequestRepository requestRepository;
    private final ApiConnectorRepository connectorRepository;
    private final ApiConnectorUserPermissionRepository permissionRepository;
    private final ApiReviewDecisionRepository decisionRepository;
    private final ApiSchemaService schemaService;
    private final ApiRequestStateService stateService;
    private final ApiExecutionService executionService;
    private final BreakGlassService breakGlassService;
    private final AiAnalysisLookupService aiAnalysisLookupService;
    private final UserQueryService userQueryService;
    private final ApigovRequestProperties requestProperties;
    private final AuditLogService auditLogService;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional
    public ApiRequestSubmissionResult submit(SubmitApiRequestCommand command) {
        var connector = connectorRepository.findByIdAndOrganizationId(command.connectorId(), command.organizationId())
                .orElseThrow(() -> new ApiConnectorNotFoundException(command.connectorId()));
        if (!connector.isActive()) {
            throw new ApiRequestValidationException("Connector is inactive");
        }
        boolean write = classifyWrite(connector, command.organizationId(), command.operationId(), command.verb());
        boolean breakGlass = command.submissionReason() == SubmissionReason.EMERGENCY_ACCESS;
        var permission = enforcePermission(connector, command, write, breakGlass);
        validateAgainstSchema(connector, command);
        var bodyType = command.bodyType() == null ? ApiBodyType.RAW : command.bodyType();
        enforceBodySize(bodyType, command);

        var entity = new ApiRequestEntity();
        entity.setId(UUID.randomUUID());
        entity.setConnectorId(connector.getId());
        entity.setOrganizationId(command.organizationId());
        entity.setSubmittedBy(command.submitterUserId());
        entity.setOperationId(command.operationId());
        entity.setVerb(command.verb());
        entity.setRequestPath(command.requestPath());
        entity.setRequestHeaders(writeJson(command.requestHeaders()));
        entity.setQueryParams(writeJson(command.queryParams()));
        entity.setBodyType(bodyType);
        entity.setRequestContentType(command.requestContentType());
        entity.setRequestBody(command.requestBody());
        entity.setFormFields(writeFormFields(command.formFields()));
        entity.setBinaryFilename(command.binaryFilename());
        entity.setTraceId(TraceContext.newTraceId());
        entity.setSpanId(TraceContext.newSpanId());
        entity.setWrite(write);
        entity.setJustification(command.justification());
        entity.setScheduledFor(command.scheduledFor());
        entity.setSubmissionReason(command.submissionReason() != null
                ? command.submissionReason() : SubmissionReason.USER_SUBMITTED);
        entity.setSubmittedIp(command.submittedIp());
        entity.setSubmittedUserAgent(command.submittedUserAgent());
        entity.setStatus(QueryStatus.PENDING_AI);
        requestRepository.save(entity);

        if (breakGlass) {
            return breakGlassExecute(connector, entity, command, permission);
        }
        audit(AuditAction.API_REQUEST_SUBMITTED, entity, command.submittedIp(), command.submittedUserAgent(),
                Map.of("verb", command.verb(), "write", write));
        eventPublisher.publishEvent(new ApiRequestSubmittedEvent(entity.getId()));
        return new ApiRequestSubmissionResult(entity.getId(), entity.getStatus());
    }

    private ApiRequestSubmissionResult breakGlassExecute(ApiConnectorEntity connector, ApiRequestEntity entity,
                                                         SubmitApiRequestCommand command,
                                                         ApiConnectorUserPermissionEntity permission) {
        if (permission == null || !permission.isCanBreakGlass()) {
            throw new ApiRequestPermissionException("Break-glass is not permitted for this connector");
        }
        stateService.apply(entity, QueryStatus.APPROVED);
        var executed = executionService.execute(entity.getId());
        breakGlassService.openApiBreakGlassReview(new BreakGlassService.ApiBreakGlassReview(
                command.organizationId(), entity.getId(), connector.getId(), command.submitterUserId(),
                command.justification()));
        audit(AuditAction.API_REQUEST_BREAK_GLASS_EXECUTED, executed, command.submittedIp(),
                command.submittedUserAgent(), Map.of("verb", command.verb()));
        return new ApiRequestSubmissionResult(executed.getId(), executed.getStatus());
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<ApiRequestView> list(ApiRequestListFilter filter, PageRequest pageRequest) {
        return toPage(requestRepository.findAll(ApiRequestSpecifications.forFilter(filter),
                toPageable(pageRequest)));
    }

    @Override
    @Transactional(readOnly = true)
    public ApiRequestView get(UUID id, UUID organizationId, UUID userId, boolean admin) {
        var entity = require(id, organizationId);
        if (!admin && !entity.getSubmittedBy().equals(userId)) {
            throw new ApiRequestNotFoundException(id);
        }
        return toDetailView(entity);
    }

    @Override
    @Transactional
    public void cancel(UUID id, UUID organizationId, UUID userId) {
        var entity = require(id, organizationId);
        if (!entity.getSubmittedBy().equals(userId)) {
            throw new ApiRequestPermissionException("Only the submitter can cancel an API request");
        }
        boolean cancellable = entity.getStatus() == QueryStatus.PENDING_AI
                || entity.getStatus() == QueryStatus.PENDING_REVIEW
                || (entity.getStatus() == QueryStatus.APPROVED && entity.getScheduledFor() != null);
        if (!cancellable) {
            throw new IllegalApiRequestStateException(entity.getStatus(), "API request cannot be cancelled");
        }
        stateService.apply(entity, QueryStatus.CANCELLED);
        audit(AuditAction.API_REQUEST_CANCELLED, entity, null, null, Map.of());
    }

    @Override
    @Transactional
    public ApiRequestView execute(UUID id, UUID organizationId, UUID userId, boolean admin) {
        var entity = require(id, organizationId);
        if (!admin && !entity.getSubmittedBy().equals(userId)) {
            throw new ApiRequestPermissionException("Only the submitter can execute an API request");
        }
        var executed = executionService.execute(id);
        audit(AuditAction.API_REQUEST_EXECUTED, executed, null, null,
                Map.of("status", executed.getStatus().name()));
        return toDetailView(executed);
    }

    private ApiConnectorUserPermissionEntity enforcePermission(ApiConnectorEntity connector,
                                                               SubmitApiRequestCommand command, boolean write,
                                                               boolean breakGlass) {
        var permission = permissionRepository.findByConnectorIdAndUserId(connector.getId(), command.submitterUserId())
                .filter(p -> p.getExpiresAt() == null || p.getExpiresAt().isAfter(Instant.now()))
                .orElse(null);
        if (command.admin() && !breakGlass) {
            return permission;
        }
        if (permission == null) {
            throw new ApiRequestPermissionException("No active permission on this connector");
        }
        if (breakGlass) {
            return permission;
        }
        if (write && !permission.isCanWrite()) {
            throw new ApiRequestPermissionException("Write access not granted on this connector");
        }
        if (!write && !permission.isCanRead()) {
            throw new ApiRequestPermissionException("Read access not granted on this connector");
        }
        var allowed = permission.getAllowedOperations();
        if (allowed != null && allowed.length > 0 && command.operationId() != null
                && !List.of(allowed).contains(command.operationId())) {
            throw new ApiRequestPermissionException("Operation not in your allow-list for this connector");
        }
        return permission;
    }

    private void validateAgainstSchema(ApiConnectorEntity connector, SubmitApiRequestCommand command) {
        if (command.operationId() == null) {
            return;
        }
        var ops = schemaService.listOperations(connector.getId(), connector.getOrganizationId());
        if (!ops.isEmpty() && ops.stream().noneMatch(o -> command.operationId().equals(o.operationId()))) {
            throw new ApiRequestValidationException(
                    "Operation '" + command.operationId() + "' is not in the connector schema");
        }
    }

    private boolean classifyWrite(ApiConnectorEntity connector, UUID organizationId, String operationId,
                                  String verb) {
        if (operationId != null) {
            var ops = schemaService.listOperations(connector.getId(), organizationId);
            var match = ops.stream().filter(o -> operationId.equals(o.operationId())).findFirst();
            if (match.isPresent()) {
                return match.map(ApiOperation::write).orElse(true);
            }
        }
        if (connector.getProtocol() == ApiProtocol.REST) {
            return verb != null && MUTATING_VERBS.contains(verb.toUpperCase());
        }
        // SOAP/GraphQL/gRPC without a matching operation: treat as write (fail-safe to review).
        return true;
    }

    private ApiRequestEntity require(UUID id, UUID organizationId) {
        return requestRepository.findByIdAndOrganizationId(id, organizationId)
                .orElseThrow(() -> new ApiRequestNotFoundException(id));
    }

    private void audit(AuditAction action, ApiRequestEntity entity, String ip, String userAgent,
                       Map<String, Object> extra) {
        var metadata = new HashMap<String, Object>(extra);
        metadata.put("connector_id", entity.getConnectorId().toString());
        metadata.put("path", entity.getRequestPath());
        try {
            auditLogService.record(new AuditEntry(action, AuditResourceType.API_REQUEST, entity.getId(),
                    entity.getOrganizationId(), entity.getSubmittedBy(), metadata, ip, userAgent));
        } catch (RuntimeException ignored) {
            // Audit must never fail the request flow.
        }
    }

    private PageResponse<ApiRequestView> toPage(Page<ApiRequestEntity> page) {
        var content = page.getContent().stream().map(this::toListView).toList();
        return new PageResponse<>(content, page.getNumber(), page.getSize() <= 0 ? 1 : page.getSize(),
                page.getTotalElements(), page.getTotalPages());
    }

    private ApiRequestView toListView(ApiRequestEntity e) {
        return buildView(e, false);
    }

    private ApiRequestView toDetailView(ApiRequestEntity e) {
        return buildView(e, true);
    }

    private ApiRequestView buildView(ApiRequestEntity e, boolean detail) {
        var connectorName = connectorRepository.findById(e.getConnectorId())
                .map(ApiConnectorEntity::getName).orElse(null);
        var submitterEmail = userQueryService.findById(e.getSubmittedBy())
                .map(UserView::email).orElse(null);
        var summary = e.getAiAnalysisId() != null
                ? aiAnalysisLookupService.findById(e.getAiAnalysisId()).orElse(null) : null;
        List<ApiReviewDecisionView> decisions = detail
                ? decisionRepository.findByApiRequestIdOrderByStageAscDecidedAtAsc(e.getId()).stream()
                        .map(d -> new ApiReviewDecisionView(d.getId(), d.getReviewerId(), d.getDecision(),
                                d.getComment(), d.getStage(), d.getDecidedAt()))
                        .toList()
                : List.of();
        var fullSnapshot = detail ? e.getResponseSnapshot() : null;
        int previewLimit = (int) Math.min(requestProperties.responsePreviewBytes(), Integer.MAX_VALUE);
        boolean previewTruncated = fullSnapshot != null && fullSnapshot.length() > previewLimit;
        var snapshotPreview = previewTruncated ? fullSnapshot.substring(0, previewLimit) : fullSnapshot;
        return new ApiRequestView(e.getId(), e.getConnectorId(), connectorName, e.getSubmittedBy(),
                submitterEmail, e.getOperationId(), e.getVerb(), e.getRequestPath(), e.isWrite(),
                e.getStatus(), e.getSubmissionReason(), e.getJustification(), e.getAiAnalysisId(),
                summary != null ? summary.riskLevel() : null,
                summary != null ? summary.riskScore() : null,
                summary != null ? summary.summary() : null,
                e.getBodyType(), e.getScheduledFor(), e.getTraceId(), e.getSpanId(),
                e.getResponseStatusCode(), e.getResponseDurationMs(),
                e.getResponseBytes(), e.isResponseTruncated(), snapshotPreview, previewTruncated,
                e.getResponseContentType(), e.getErrorMessage(), e.getCreatedAt(), decisions);
    }

    @Override
    @Transactional(readOnly = true)
    public ApiResponsePayload downloadResponse(UUID id, UUID organizationId, UUID userId, boolean admin) {
        var entity = require(id, organizationId);
        if (!admin && !entity.getSubmittedBy().equals(userId)) {
            throw new ApiRequestNotFoundException(id);
        }
        var snapshot = entity.getResponseSnapshot();
        if (snapshot == null) {
            throw new IllegalApiRequestStateException(entity.getStatus(), "API request has no stored response");
        }
        var contentType = entity.getResponseContentType() != null && !entity.getResponseContentType().isBlank()
                ? entity.getResponseContentType() : "application/octet-stream";
        var filename = "api-response-" + entity.getId() + extensionFor(contentType);
        return new ApiResponsePayload(snapshot.getBytes(StandardCharsets.UTF_8), contentType, filename);
    }

    private static String extensionFor(String contentType) {
        var ct = contentType.toLowerCase();
        if (ct.contains("json")) {
            return ".json";
        }
        if (ct.contains("xml")) {
            return ".xml";
        }
        if (ct.contains("html")) {
            return ".html";
        }
        if (ct.contains("csv")) {
            return ".csv";
        }
        if (ct.contains("text/")) {
            return ".txt";
        }
        return ".bin";
    }

    private void enforceBodySize(ApiBodyType bodyType, SubmitApiRequestCommand command) {
        long bytes = switch (bodyType) {
            case NONE -> 0L;
            case RAW -> command.requestBody() == null ? 0L
                    : command.requestBody().getBytes(StandardCharsets.UTF_8).length;
            case BINARY -> decodedLength(command.requestBody());
            case FORM_URLENCODED, FORM_DATA -> formFieldsSize(command.formFields());
        };
        if (bytes > requestProperties.maxRequestBodyBytes()) {
            throw new ApiRequestValidationException("Request body exceeds the maximum allowed size of "
                    + requestProperties.maxRequestBodyBytes() + " bytes");
        }
    }

    private static long formFieldsSize(List<ApiFormField> fields) {
        if (fields == null) {
            return 0L;
        }
        long total = 0L;
        for (var field : fields) {
            if (field == null) {
                continue;
            }
            if (field.type() == ApiFormField.ApiFormFieldType.FILE) {
                total += decodedLength(field.value());
            } else if (field.value() != null) {
                total += field.value().getBytes(StandardCharsets.UTF_8).length;
            }
        }
        return total;
    }

    private static long decodedLength(String base64) {
        if (base64 == null || base64.isBlank()) {
            return 0L;
        }
        try {
            return Base64.getDecoder().decode(base64.strip()).length;
        } catch (IllegalArgumentException ex) {
            throw new ApiRequestValidationException("Request body is not valid base64");
        }
    }

    private String writeFormFields(List<ApiFormField> fields) {
        return objectMapper.writeValueAsString(fields == null ? List.of() : fields);
    }

    private String writeJson(Map<String, String> map) {
        return objectMapper.writeValueAsString(map == null ? Map.of() : map);
    }

    private static Pageable toPageable(PageRequest pageRequest) {
        return org.springframework.data.domain.PageRequest.of(pageRequest.page(), pageRequest.size());
    }
}
