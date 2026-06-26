package com.bablsoft.accessflow.apigov.api;

/**
 * Type of an uploaded API schema document, dispatched to the matching parser
 * (OpenAPI → swagger-parser, WSDL → wsdl4j, GraphQL SDL → graphql-java, gRPC proto → wire-schema).
 */
public enum ApiSchemaType {
    OPENAPI,
    WSDL,
    GRAPHQL_SDL,
    GRPC_PROTO
}
