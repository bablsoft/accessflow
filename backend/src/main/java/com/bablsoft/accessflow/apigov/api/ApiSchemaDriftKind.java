package com.bablsoft.accessflow.apigov.api;

/** Kind of operation-level difference between a fetched schema revision and the accepted catalog. */
public enum ApiSchemaDriftKind {
    OPERATION_ADDED,
    OPERATION_REMOVED,
    SHAPE_CHANGED,
    DEPRECATED,
    CLASSIFICATION_FLIPPED
}
