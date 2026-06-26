package com.bablsoft.accessflow.apigov.internal.schema;

import com.bablsoft.accessflow.apigov.api.ApiOperation;
import com.bablsoft.accessflow.apigov.api.ApiSchemaType;

import java.util.List;

/**
 * SPI for parsing an uploaded API schema document into a normalized {@link ApiOperation} catalog.
 * One implementation per {@link ApiSchemaType}; the {@code SchemaParserRegistry} dispatches by type.
 */
public interface ApiSchemaParser {

    ApiSchemaType supportedType();

    /**
     * Parses {@code content} into a normalized operation catalog.
     *
     * @throws com.bablsoft.accessflow.apigov.api.ApiSchemaParseException if the document is invalid
     */
    List<ApiOperation> parse(String content);
}
