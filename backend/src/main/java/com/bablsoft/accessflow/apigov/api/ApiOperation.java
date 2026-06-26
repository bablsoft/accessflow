package com.bablsoft.accessflow.apigov.api;

/**
 * One normalized operation from a parsed API schema, protocol-agnostic. {@code verb} is the HTTP
 * method (REST), the GraphQL operation type (query/mutation), the SOAP operation name, or the gRPC
 * {@code service.method}. {@code path} is the route/template. {@code write} reflects the read/write
 * classification (read = GET/HEAD/OPTIONS/GraphQL query; write = mutating verbs / GraphQL mutation /
 * gRPC unary writes) so the same review plans and permissions apply uniformly.
 */
public record ApiOperation(
        String operationId,
        String verb,
        String path,
        String summary,
        boolean write,
        String requestSchema,
        String responseSchema) {
}
