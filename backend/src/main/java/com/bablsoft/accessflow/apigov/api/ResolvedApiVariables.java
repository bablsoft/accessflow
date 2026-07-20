package com.bablsoft.accessflow.apigov.api;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * The evaluated connector variables for one outbound call (AF-613): the value of each variable by
 * name, plus the auto-injection targets that must be applied after substitution.
 *
 * <p>Resolved values are execution-time only. They are never persisted onto the request row, never
 * written to the response snapshot, and never logged — the resolver cannot tell a signature (not
 * sensitive) from a {@code CONSTANT} holding a shared secret (very much sensitive), so all of them
 * are treated as sensitive. {@link #values()} exists so callers can scrub them out of any error
 * message before it is persisted or logged.
 */
public record ResolvedApiVariables(
        Map<String, String> values,
        List<ApiVariableInjection> injections) {

    public ResolvedApiVariables {
        values = values == null ? Map.of() : Map.copyOf(values);
        injections = injections == null ? List.of() : List.copyOf(injections);
    }

    public static ResolvedApiVariables empty() {
        return new ResolvedApiVariables(Map.of(), List.of());
    }

    public boolean isEmpty() {
        return values.isEmpty() && injections.isEmpty();
    }

    /** The resolved values, for redaction of outbound error text. Never render these to a user. */
    public Collection<String> secretValues() {
        return values.values();
    }

    /** An auto-injection of a resolved value into a header or query parameter. */
    public record ApiVariableInjection(ApiVariableTargetType type, String key, String value) {
    }
}
