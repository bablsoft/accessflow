package com.bablsoft.accessflow.requestgroups.internal.web;

import com.bablsoft.accessflow.requestgroups.api.RequestGroupItemInput;
import com.bablsoft.accessflow.requestgroups.api.RequestGroupTargetKind;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** One ordered member in a create/update group request. Query vs API fields per {@code targetKind}. */
record RequestGroupItemRequest(
        @NotNull(message = "{validation.request_group_item.target_kind.required}")
        RequestGroupTargetKind targetKind,
        // QUERY
        UUID datasourceId,
        @Size(max = 100000, message = "{validation.request_group_item.sql.size}")
        String sqlText,
        boolean transactional,
        // API_CALL
        UUID apiConnectorId,
        @Size(max = 255, message = "{validation.request_group_item.operation.size}")
        String operationId,
        @Size(max = 16, message = "{validation.request_group_item.verb.size}")
        String verb,
        @Size(max = 4000, message = "{validation.request_group_item.path.size}")
        String requestPath,
        Map<String, String> requestHeaders,
        Map<String, String> queryParams,
        RequestGroupItemInput.ApiBodyKind bodyType,
        @Size(max = 255, message = "{validation.request_group_item.content_type.size}")
        String requestContentType,
        String requestBody,
        List<FormFieldRequest> formFields,
        @Size(max = 255, message = "{validation.request_group_item.filename.size}")
        String binaryFilename) {

    record FormFieldRequest(String name, String value, boolean file, String filename, String contentType) {
    }

    RequestGroupItemInput toInput(int order) {
        var fields = formFields == null ? List.<RequestGroupItemInput.ApiFormFieldInput>of()
                : formFields.stream()
                        .map(f -> new RequestGroupItemInput.ApiFormFieldInput(f.name(), f.value(),
                                f.file(), f.filename(), f.contentType()))
                        .toList();
        return new RequestGroupItemInput(targetKind, order, datasourceId, sqlText, transactional,
                apiConnectorId, operationId, verb, requestPath, requestHeaders, queryParams, bodyType,
                requestContentType, requestBody, fields, binaryFilename);
    }
}
