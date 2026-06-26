package com.bablsoft.accessflow.apigov.internal.schema;

import com.bablsoft.accessflow.apigov.api.ApiOperation;
import com.bablsoft.accessflow.apigov.api.ApiSchemaParseException;
import com.bablsoft.accessflow.apigov.api.ApiSchemaType;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Lightweight GraphQL SDL parser: extracts the fields of the root {@code Query} and {@code Mutation}
 * types into operations. Query fields classify as reads, mutation fields as writes. Avoids pulling in
 * graphql-java so the build stays offline-reproducible; covers the common SDL shapes.
 */
@Component
public class GraphQlSchemaParser implements ApiSchemaParser {

    private static final Pattern QUERY_BLOCK =
            Pattern.compile("type\\s+Query\\s*\\{([^}]*)}", Pattern.DOTALL);
    private static final Pattern MUTATION_BLOCK =
            Pattern.compile("type\\s+Mutation\\s*\\{([^}]*)}", Pattern.DOTALL);
    private static final Pattern FIELD =
            Pattern.compile("(?m)^\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*(?:\\([^)]*\\))?\\s*:");

    @Override
    public ApiSchemaType supportedType() {
        return ApiSchemaType.GRAPHQL_SDL;
    }

    @Override
    public List<ApiOperation> parse(String content) {
        if (content == null || content.isBlank()) {
            throw new ApiSchemaParseException("Empty GraphQL SDL document");
        }
        var operations = new ArrayList<ApiOperation>();
        extract(content, QUERY_BLOCK, "query", false, operations);
        extract(content, MUTATION_BLOCK, "mutation", true, operations);
        if (operations.isEmpty()) {
            throw new ApiSchemaParseException("GraphQL SDL defines no Query or Mutation fields");
        }
        return operations;
    }

    private void extract(String content, Pattern block, String verb, boolean write,
                         List<ApiOperation> out) {
        var blockMatcher = block.matcher(content);
        if (!blockMatcher.find()) {
            return;
        }
        var fieldMatcher = FIELD.matcher(blockMatcher.group(1));
        while (fieldMatcher.find()) {
            var field = fieldMatcher.group(1);
            out.add(new ApiOperation(field, verb, field, null, write, null, null));
        }
    }
}
