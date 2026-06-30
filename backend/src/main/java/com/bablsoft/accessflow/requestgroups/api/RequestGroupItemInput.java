package com.bablsoft.accessflow.requestgroups.api;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * One ordered member supplied when creating / updating a group draft. Exactly one target shape is
 * populated per {@link #targetKind()}:
 * <ul>
 *     <li>{@code QUERY} — {@code datasourceId} + {@code sqlText} (+ optional {@code transactional}).</li>
 *     <li>{@code API_CALL} — {@code apiConnectorId} + {@code verb} + {@code requestPath} (+ headers,
 *         query params, body).</li>
 * </ul>
 * The remaining fields are {@code null} / empty. Query type is classified server-side from the SQL.
 */
public record RequestGroupItemInput(
        RequestGroupTargetKind targetKind,
        int sequenceOrder,
        // QUERY
        UUID datasourceId,
        String sqlText,
        boolean transactional,
        // API_CALL
        UUID apiConnectorId,
        String operationId,
        String verb,
        String requestPath,
        Map<String, String> requestHeaders,
        Map<String, String> queryParams,
        ApiBodyKind bodyType,
        String requestContentType,
        String requestBody,
        List<ApiFormFieldInput> formFields,
        String binaryFilename) {

    /** Body encoding for an {@code API_CALL} member — mirrors the AF-500 {@code api_body_type} enum. */
    public enum ApiBodyKind { NONE, RAW, FORM_DATA, FORM_URLENCODED, BINARY }

    /** A single multipart/url-encoded form field for an {@code API_CALL} member. */
    public record ApiFormFieldInput(String name, String value, boolean file, String filename,
                                    String contentType) {
    }
}
