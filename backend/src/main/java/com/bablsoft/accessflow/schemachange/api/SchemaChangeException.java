package com.bablsoft.accessflow.schemachange.api;

/** Base type for schema-change-governance domain exceptions (#878, epic #870). */
public abstract class SchemaChangeException extends RuntimeException {

    protected SchemaChangeException(String message) {
        super(message);
    }
}
