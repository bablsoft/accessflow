package com.bablsoft.accessflow.apigov.internal.client;

import com.bablsoft.accessflow.apigov.api.ApiBodyType;
import com.bablsoft.accessflow.apigov.api.ApiFormField;
import com.bablsoft.accessflow.apigov.api.ApiProtocol;

import java.util.List;
import java.util.Map;

/**
 * Fully composed outbound call handed to {@link ApiCallExecutor}: target + headers plus the body
 * composition ({@code bodyType} with its raw text / form parts / base64 binary) and query parameters.
 * Built by {@code ApiExecutionService} from the stored API request and its connector.
 */
public record ApiCallRequest(
        ApiProtocol protocol,
        String baseUrl,
        String verb,
        String path,
        Map<String, String> headers,
        Map<String, String> queryParams,
        ApiBodyType bodyType,
        String body,
        String contentType,
        List<ApiFormField> formFields,
        String binaryFilename,
        int timeoutMs,
        long maxResponseBytes,
        String operationId) {
}
