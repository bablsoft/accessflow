package com.bablsoft.accessflow.apigov.internal.schema;

import com.bablsoft.accessflow.apigov.api.ApiOperation;
import com.bablsoft.accessflow.apigov.api.ApiSchemaParseException;
import com.bablsoft.accessflow.apigov.api.ApiSchemaType;
import com.bablsoft.accessflow.apigov.api.ParsedApiSchema;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lightweight gRPC {@code .proto} parser: extracts {@code service}/{@code rpc} declarations into
 * {@code service.method} operations. Read/write is heuristic on the method-name prefix
 * (get/list/describe/read/search/query/find/watch → read, else write). Avoids the wire-schema /
 * protoc toolchain so the build stays offline-reproducible.
 */
@Component
public class ProtoSchemaParser implements ApiSchemaParser {

    private static final Pattern SERVICE =
            Pattern.compile("service\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*\\{([^}]*)}", Pattern.DOTALL);
    private static final Pattern RPC =
            Pattern.compile("rpc\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*\\(\\s*(?:stream\\s+)?([^)]*)\\)\\s*"
                    + "returns\\s*\\(\\s*(?:stream\\s+)?([^)]*)\\)");
    private static final Set<String> READ_PREFIXES =
            Set.of("get", "list", "describe", "read", "search", "query", "find", "watch", "fetch");

    @Override
    public ApiSchemaType supportedType() {
        return ApiSchemaType.GRPC_PROTO;
    }

    @Override
    public ParsedApiSchema parse(String content) {
        if (content == null || content.isBlank()) {
            throw new ApiSchemaParseException("Empty proto document");
        }
        var operations = new ArrayList<ApiOperation>();
        var serviceMatcher = SERVICE.matcher(content);
        while (serviceMatcher.find()) {
            var service = serviceMatcher.group(1);
            var rpcMatcher = RPC.matcher(serviceMatcher.group(2));
            while (rpcMatcher.find()) {
                var method = rpcMatcher.group(1);
                var verb = service + "." + method;
                operations.add(new ApiOperation(verb, verb, method, null, isWrite(method),
                        trim(rpcMatcher, 2), trim(rpcMatcher, 3)));
            }
        }
        if (operations.isEmpty()) {
            throw new ApiSchemaParseException("proto document defines no service rpc methods");
        }
        return new ParsedApiSchema(operations);
    }

    private static boolean isWrite(String method) {
        var lower = method.toLowerCase();
        return READ_PREFIXES.stream().noneMatch(lower::startsWith);
    }

    private static String trim(Matcher m, int group) {
        var v = m.group(group);
        return v == null ? null : v.trim();
    }
}
