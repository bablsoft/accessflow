package com.bablsoft.accessflow.apigov.internal;

import com.bablsoft.accessflow.apigov.api.ApiConnectorVariableLookupService;
import com.bablsoft.accessflow.apigov.api.ApiFormField;
import com.bablsoft.accessflow.apigov.api.ApiReplayOperationUnavailableException;
import com.bablsoft.accessflow.apigov.api.ApiRequestNotFoundException;
import com.bablsoft.accessflow.apigov.api.ApiRequestReplayProvenance;
import com.bablsoft.accessflow.apigov.api.ApiRequestReplayService;
import com.bablsoft.accessflow.apigov.api.ApiRequestService;
import com.bablsoft.accessflow.apigov.api.ApiSchemaService;
import com.bablsoft.accessflow.apigov.api.IllegalApiRequestStateException;
import com.bablsoft.accessflow.apigov.api.SubmitApiRequestCommand;
import com.bablsoft.accessflow.apigov.internal.persistence.entity.ApiRequestEntity;
import com.bablsoft.accessflow.apigov.internal.persistence.repo.ApiRequestRepository;
import com.bablsoft.accessflow.core.api.Permission;
import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.core.api.SubmissionReason;
import lombok.RequiredArgsConstructor;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
class DefaultApiRequestReplayService implements ApiRequestReplayService {

    private static final Set<QueryStatus> TERMINAL = Set.of(QueryStatus.EXECUTED, QueryStatus.FAILED,
            QueryStatus.REJECTED, QueryStatus.TIMED_OUT, QueryStatus.CANCELLED);
    private static final TypeReference<Map<String, String>> STRING_MAP_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<List<ApiFormField>> FORM_FIELDS_TYPE = new TypeReference<>() {
    };

    private final ApiRequestRepository requestRepository;
    private final ApiSchemaService schemaService;
    private final EffectiveApiConnectorPermissionResolver permissionResolver;
    private final ApiConnectorVariableLookupService variableLookupService;
    private final ApiRequestService requestService;
    private final MessageSource messageSource;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional
    public ReplayResult replay(ReplayCommand command) {
        var source = requestRepository
                .findByIdAndOrganizationId(command.originalApiRequestId(), command.organizationId())
                .filter(r -> canView(r, command))
                .orElseThrow(() -> new ApiRequestNotFoundException(command.originalApiRequestId()));
        if (!TERMINAL.contains(source.getStatus())) {
            throw new IllegalApiRequestStateException(source.getStatus(),
                    "Only a terminal API request can be replayed");
        }
        requireOperationAvailable(source);

        var storedOverrides = readMap(source.getVariableOverrides());
        var overrides = carriedOverrides(source, command, storedOverrides);
        int dropped = storedOverrides.size() - overrides.size();

        var result = requestService.submit(new SubmitApiRequestCommand(
                source.getConnectorId(),
                command.organizationId(),
                command.callerUserId(),
                command.admin(),
                source.getOperationId(),
                source.getVerb(),
                source.getRequestPath(),
                readMap(source.getRequestHeaders()),
                readMap(source.getQueryParams()),
                source.getBodyType(),
                source.getRequestContentType(),
                source.getRequestBody(),
                readFormFields(source.getFormFields()),
                source.getBinaryFilename(),
                overrides,
                messageSource.getMessage("replay.api_justification", new Object[]{source.getId()},
                        LocaleContextHolder.getLocale()),
                null,
                SubmissionReason.USER_SUBMITTED,
                command.ipAddress(),
                command.userAgent(),
                command.onBehalfOfUserId(),
                new ApiRequestReplayProvenance(source.getId(), dropped)));
        return new ReplayResult(result.id(), result.status(), dropped);
    }

    private static boolean canView(ApiRequestEntity source, ReplayCommand command) {
        // Same rule as ApiRequestService.get: the submitter, or "View all query history".
        return source.getSubmittedBy().equals(command.callerUserId())
                || (command.callerPermissions() != null
                        && command.callerPermissions().contains(Permission.QUERY_VIEW_ALL));
    }

    private void requireOperationAvailable(ApiRequestEntity source) {
        var operationId = source.getOperationId();
        if (operationId == null) {
            return;
        }
        // listOperations applies the connector's import filter; an empty catalog means no schema,
        // which submit accepts too.
        var ops = schemaService.listOperations(source.getConnectorId(), source.getOrganizationId());
        if (!ops.isEmpty() && ops.stream().noneMatch(o -> operationId.equals(o.operationId()))) {
            throw new ApiReplayOperationUnavailableException(operationId);
        }
    }

    private Map<String, String> carriedOverrides(ApiRequestEntity source, ReplayCommand command,
                                                 Map<String, String> stored) {
        if (stored.isEmpty()) {
            return Map.of();
        }
        var permission = permissionResolver.resolve(source.getConnectorId(), command.callerUserId());
        if (permission.isEmpty() || !permission.get().canOverrideVariables()) {
            return Map.of();
        }
        var overridable = variableLookupService.overridableNames(source.getConnectorId(),
                command.organizationId());
        var carried = new LinkedHashMap<String, String>();
        stored.forEach((name, value) -> {
            if (overridable.contains(name)) {
                carried.put(name, value);
            }
        });
        return carried;
    }

    private Map<String, String> readMap(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, STRING_MAP_TYPE);
        } catch (RuntimeException ex) {
            return Map.of();
        }
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
}
