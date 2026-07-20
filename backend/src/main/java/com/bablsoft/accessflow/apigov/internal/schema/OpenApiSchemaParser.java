package com.bablsoft.accessflow.apigov.internal.schema;

import com.bablsoft.accessflow.apigov.api.ApiOperation;
import com.bablsoft.accessflow.apigov.api.ApiSchemaParseException;
import com.bablsoft.accessflow.apigov.api.ApiSchemaType;
import io.swagger.parser.OpenAPIParser;
import io.swagger.v3.parser.core.models.SwaggerParseResult;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Parses an OpenAPI 2/3 document (JSON or YAML) into a normalized operation catalog via
 * swagger-parser. Safe methods (GET/HEAD/OPTIONS) classify as reads; everything else is a write.
 */
@Component
public class OpenApiSchemaParser implements ApiSchemaParser {

    private static final Set<String> READ_METHODS = Set.of("GET", "HEAD", "OPTIONS");

    @Override
    public ApiSchemaType supportedType() {
        return ApiSchemaType.OPENAPI;
    }

    @Override
    public List<ApiOperation> parse(String content) {
        SwaggerParseResult result;
        try {
            result = new OpenAPIParser().readContents(content, null, null);
        } catch (RuntimeException ex) {
            throw new ApiSchemaParseException("Invalid OpenAPI document: " + ex.getMessage());
        }
        var openApi = result != null ? result.getOpenAPI() : null;
        if (openApi == null || openApi.getPaths() == null || openApi.getPaths().isEmpty()) {
            var messages = result != null && result.getMessages() != null
                    ? String.join("; ", result.getMessages()) : "no paths found";
            throw new ApiSchemaParseException("Invalid OpenAPI document: " + messages);
        }
        var operations = new ArrayList<ApiOperation>();
        openApi.getPaths().forEach((path, item) ->
                item.readOperationsMap().forEach((method, op) -> {
                    var verb = method.name().toUpperCase();
                    var opId = op.getOperationId() != null && !op.getOperationId().isBlank()
                            ? op.getOperationId() : verb + " " + path;
                    boolean write = !READ_METHODS.contains(verb);
                    var tags = op.getTags() != null ? List.copyOf(op.getTags()) : null;
                    operations.add(new ApiOperation(opId, verb, path, op.getSummary(), write, null, null,
                            tags, op.getDeprecated()));
                }));
        if (operations.isEmpty()) {
            throw new ApiSchemaParseException("OpenAPI document defines no operations");
        }
        return operations;
    }
}
