package com.bablsoft.accessflow.apigov.internal;

import com.bablsoft.accessflow.apigov.api.ApiRequestNotFoundException;
import com.bablsoft.accessflow.apigov.api.ApiResponseDiffCommand;
import com.bablsoft.accessflow.apigov.api.ApiResponseDiffEntry;
import com.bablsoft.accessflow.apigov.api.ApiResponseDiffFormat;
import com.bablsoft.accessflow.apigov.api.ApiResponseDiffIncompatibleException;
import com.bablsoft.accessflow.apigov.api.ApiResponseDiffKind;
import com.bablsoft.accessflow.apigov.api.ApiResponseDiffNotAvailableException;
import com.bablsoft.accessflow.apigov.api.ApiResponseDiffService;
import com.bablsoft.accessflow.apigov.api.ApiResponseDiffUnsupportedException;
import com.bablsoft.accessflow.apigov.api.ApiResponseDiffView;
import com.bablsoft.accessflow.apigov.internal.config.ApigovRequestProperties;
import com.bablsoft.accessflow.apigov.internal.persistence.entity.ApiRequestEntity;
import com.bablsoft.accessflow.apigov.internal.persistence.repo.ApiRequestRepository;
import com.bablsoft.accessflow.core.api.QueryStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * Diffs the stored, masked snapshots of two runs of the same connector + operation (#887). "Previous"
 * is derived at read time and, for a caller without {@code QUERY_VIEW_ALL}, only among the caller's
 * own runs; an explicit other side passes the same visibility guard (404, never 403).
 */
@Service
class DefaultApiResponseDiffService implements ApiResponseDiffService {

    private final ApiRequestRepository requestRepository;
    private final ApigovRequestProperties requestProperties;
    private final ApiResponseStructuralDiffer differ;

    DefaultApiResponseDiffService(ApiRequestRepository requestRepository,
                                  ApigovRequestProperties requestProperties, ObjectMapper objectMapper) {
        this.requestRepository = requestRepository;
        this.requestProperties = requestProperties;
        this.differ = new ApiResponseStructuralDiffer(objectMapper);
    }

    @Override
    @Transactional(readOnly = true)
    public ApiResponseDiffView diff(ApiResponseDiffCommand command) {
        var current = loadVisible(command.apiRequestId(), command);
        if (current.getResponseSnapshot() == null) {
            throw new ApiResponseDiffNotAvailableException(current.getId());
        }
        var previous = command.againstApiRequestId() == null
                ? findPrevious(current, command)
                : loadComparable(current, command);
        var contentType = current.getResponseContentType();
        var format = detectFormat(contentType, current.getResponseSnapshot());
        var result = differ.diff(previous.getResponseSnapshot(), previous.getResponseContentType(),
                current.getResponseSnapshot(), contentType, format, requestProperties.maxDiffEntries());
        return new ApiResponseDiffView(current.getId(), previous.getId(), format,
                current.getResponseStatusCode(), previous.getResponseStatusCode(),
                subtract(current.getResponseDurationMs(), previous.getResponseDurationMs()),
                subtract(current.getResponseBytes(), previous.getResponseBytes()),
                count(result.entries(), ApiResponseDiffKind.ADDED),
                count(result.entries(), ApiResponseDiffKind.REMOVED),
                count(result.entries(), ApiResponseDiffKind.CHANGED),
                result.truncated(),
                current.isResponseTruncated() || previous.isResponseTruncated(),
                result.entries());
    }

    private ApiRequestEntity loadVisible(UUID id, ApiResponseDiffCommand command) {
        var entity = requestRepository.findByIdAndOrganizationId(id, command.organizationId())
                .orElseThrow(() -> new ApiRequestNotFoundException(id));
        if (!ApiRequestVisibility.canView(entity, command.userId(), command.callerPermissions())) {
            throw new ApiRequestNotFoundException(id);
        }
        return entity;
    }

    private ApiRequestEntity findPrevious(ApiRequestEntity current, ApiResponseDiffCommand command) {
        var submittedBy = ApiRequestVisibility.canViewAll(command.callerPermissions()) ? null : command.userId();
        var previous = current.getOperationId() != null
                ? requestRepository.findPreviousRunByOperation(current.getOrganizationId(),
                        current.getConnectorId(), current.getOperationId(), current.getCreatedAt(),
                        current.getId(), submittedBy)
                : requestRepository.findPreviousRunByVerbAndPath(current.getOrganizationId(),
                        current.getConnectorId(), current.getVerb(), current.getRequestPath(),
                        current.getCreatedAt(), current.getId(), submittedBy);
        return previous.orElseThrow(() -> new ApiResponseDiffNotAvailableException(current.getId()));
    }

    private ApiRequestEntity loadComparable(ApiRequestEntity current, ApiResponseDiffCommand command) {
        var other = loadVisible(command.againstApiRequestId(), command);
        var sameOperation = current.getOperationId() != null
                ? current.getOperationId().equals(other.getOperationId())
                : other.getOperationId() == null && Objects.equals(current.getVerb(), other.getVerb())
                        && Objects.equals(current.getRequestPath(), other.getRequestPath());
        var comparable = !other.getId().equals(current.getId())
                && Objects.equals(current.getConnectorId(), other.getConnectorId())
                && sameOperation
                && other.getStatus() == QueryStatus.EXECUTED
                && other.getResponseSnapshot() != null
                && other.getCreatedAt().isBefore(current.getCreatedAt());
        if (!comparable) {
            throw new ApiResponseDiffIncompatibleException(current.getId(), other.getId());
        }
        return other;
    }

    /**
     * The content type decides when it names JSON or XML; only a missing one falls back to the body's
     * shape, so a {@code text/csv} or {@code text/html} body is never coerced into a format.
     */
    static ApiResponseDiffFormat detectFormat(String contentType, String body) {
        if (contentType != null && !contentType.isBlank()) {
            var ct = contentType.toLowerCase(Locale.ROOT);
            if (ct.contains("json")) {
                return ApiResponseDiffFormat.JSON;
            }
            if (ct.contains("xml")) {
                return ApiResponseDiffFormat.XML;
            }
            throw new ApiResponseDiffUnsupportedException(contentType);
        }
        var trimmed = body.stripLeading();
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            return ApiResponseDiffFormat.JSON;
        }
        if (ApiResponseMasker.looksLikeXml(null, trimmed)) {
            return ApiResponseDiffFormat.XML;
        }
        throw new ApiResponseDiffUnsupportedException(contentType);
    }

    private static Long subtract(Number current, Number previous) {
        return current == null || previous == null ? null : current.longValue() - previous.longValue();
    }

    private static int count(List<ApiResponseDiffEntry> entries, ApiResponseDiffKind kind) {
        return (int) entries.stream().filter(e -> e.kind() == kind).count();
    }
}
