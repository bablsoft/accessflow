package com.bablsoft.accessflow.apigov.internal.schema;

import com.bablsoft.accessflow.apigov.api.ApiSchemaParseException;
import com.bablsoft.accessflow.apigov.api.ApiSchemaType;
import com.bablsoft.accessflow.apigov.api.ParsedApiSchema;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** Dispatches a schema document to the parser registered for its {@link ApiSchemaType}. */
@Component
public class SchemaParserRegistry {

    private final Map<ApiSchemaType, ApiSchemaParser> parsers = new EnumMap<>(ApiSchemaType.class);

    public SchemaParserRegistry(List<ApiSchemaParser> parserBeans) {
        for (var parser : parserBeans) {
            parsers.put(parser.supportedType(), parser);
        }
    }

    public ParsedApiSchema parse(ApiSchemaType type, String content) {
        var parser = parsers.get(type);
        if (parser == null) {
            throw new ApiSchemaParseException("No parser registered for schema type " + type);
        }
        return parser.parse(content);
    }
}
