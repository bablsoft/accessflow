package com.bablsoft.accessflow.apigov.internal.schema;

import com.bablsoft.accessflow.apigov.api.ApiSchemaType;
import com.bablsoft.accessflow.apigov.api.ParsedApiSchema;

/**
 * SPI for parsing an uploaded API schema document into a normalized operation catalog.
 * One implementation per {@link ApiSchemaType}; the {@code SchemaParserRegistry} dispatches by type.
 */
public interface ApiSchemaParser {

    ApiSchemaType supportedType();

    /**
     * Parses {@code content} into a normalized operation catalog, optionally reporting the auth
     * scheme the document declares and a sanitized document to persist in its place.
     *
     * @throws com.bablsoft.accessflow.apigov.api.ApiSchemaParseException if the document is invalid
     */
    ParsedApiSchema parse(String content);
}
