package com.bablsoft.accessflow.apigov.api;

import java.util.List;

/**
 * One normalized operation from a parsed API schema, protocol-agnostic. {@code verb} is the HTTP
 * method (REST), the GraphQL operation type (query/mutation), the SOAP operation name, or the gRPC
 * {@code service.method}. {@code path} is the route/template. {@code write} reflects the read/write
 * classification (read = GET/HEAD/OPTIONS/GraphQL query; write = mutating verbs / GraphQL mutation /
 * gRPC unary writes) so the same review plans and permissions apply uniformly. {@code tags} and
 * {@code deprecated} are OpenAPI-only import-filter dimensions ({@code null} for other protocols and
 * for schemas parsed before those fields existed).
 */
public record ApiOperation(
        String operationId,
        String verb,
        String path,
        String summary,
        boolean write,
        String requestSchema,
        String responseSchema,
        List<String> tags,
        Boolean deprecated) {

    /** Backwards-compatible constructor for parsers that do not carry tags/deprecated. */
    public ApiOperation(String operationId, String verb, String path, String summary, boolean write,
                        String requestSchema, String responseSchema) {
        this(operationId, verb, path, summary, write, requestSchema, responseSchema, null, null);
    }
}
