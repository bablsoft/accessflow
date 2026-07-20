package com.bablsoft.accessflow.apigov.api;

/**
 * Where a connector variable auto-injects its resolved value when no explicit {@code {{name}}}
 * placeholder is used (AF-613). Encoded on the row as {@code "header:<Name>"} / {@code "query:<name>"}.
 *
 * <p>There is deliberately no whole-body target: replacing an entire request body with one value is
 * never what an operator means, and partial-body injection needs a JSON pointer — a separate
 * feature. A body placeholder is spelled {@code {{name}}} in the body itself.
 */
public enum ApiVariableTargetType {
    HEADER,
    QUERY
}
