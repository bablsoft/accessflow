package com.bablsoft.accessflow.apigov.api;

/**
 * Type of an uploaded API schema document, dispatched to the matching parser (OpenAPI →
 * swagger-parser; WSDL → JDK DOM; GraphQL SDL and gRPC proto → lightweight regex parsers; Postman
 * collection → JSON tree). {@code POSTMAN_COLLECTION} accepts a Postman Collection v2.x export and
 * is the only source whose request/response schemas are <em>inferred from examples</em> rather than
 * declared.
 */
public enum ApiSchemaType {
    OPENAPI,
    WSDL,
    GRAPHQL_SDL,
    GRPC_PROTO,
    POSTMAN_COLLECTION
}
