package com.bablsoft.accessflow.apigov.api;

import java.util.Map;

/**
 * The read-only view of the in-flight outbound request that connector variable expressions resolve
 * against (AF-613), reachable as {@code {{request.method}}}, {@code {{request.path}}},
 * {@code {{request.query}}}, {@code {{request.body}}} and {@code {{request.headers.<Name>}}}.
 *
 * <p><strong>Every field denotes the request as it stands <em>before</em> any variable substitution
 * has happened.</strong> That is load-bearing, not incidental: the motivating vendor scheme signs a
 * body that still contains the literal {@code {{signature}}} placeholder, and only substitutes the
 * digest in afterwards. Resolving {@code request.body} post-substitution would compute a different
 * digest and silently produce signatures the vendor rejects.
 *
 * <p>{@code headers} is the fully merged map — connector defaults, per-request headers, trace
 * headers, and the auth header the applier just computed — so an expression may sign the finished
 * {@code Authorization} value. Lookup by {@code {{request.headers.<Name>}}} is case-insensitive.
 *
 * <p>{@code query} is a canonical serialization (keys sorted, percent-encoded, joined with
 * {@code &}) that is deliberately independent of the wire ordering, so a signature over it is
 * reproducible.
 */
public record ApiVariableRequestContext(
        String method,
        String path,
        String query,
        String body,
        Map<String, String> headers) {

    public ApiVariableRequestContext {
        method = method == null ? "" : method;
        path = path == null ? "" : path;
        query = query == null ? "" : query;
        body = body == null ? "" : body;
        headers = headers == null ? Map.of() : Map.copyOf(headers);
    }
}
