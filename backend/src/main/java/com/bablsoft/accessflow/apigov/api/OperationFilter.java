package com.bablsoft.accessflow.apigov.api;

import java.util.List;

/**
 * Import-time operation filter declared per uploaded schema. The connector's governed operation
 * catalog is the parsed operations kept by this filter. Evaluated per operation as: keep when every
 * non-empty include dimension matches AND no exclude dimension matches — <strong>exclude wins</strong>.
 *
 * <p>Path and operation-id lists are glob patterns ({@code *} matches any run of characters);
 * verb and tag lists are exact, case-insensitive matches. {@code excludeDeprecated} drops any
 * operation flagged {@code deprecated: true} (OpenAPI only). An {@link #EMPTY} filter keeps every
 * operation — exactly the pre-filter behaviour.
 */
public record OperationFilter(
        List<String> includePaths,
        List<String> excludePaths,
        List<String> includeVerbs,
        List<String> excludeVerbs,
        List<String> includeOperationIds,
        List<String> excludeOperationIds,
        List<String> includeTags,
        List<String> excludeTags,
        boolean excludeDeprecated) {

    public static final OperationFilter EMPTY =
            new OperationFilter(null, null, null, null, null, null, null, null, false);

    /** Canonicalizes null lists to empty and copies to unmodifiable lists. */
    public OperationFilter {
        includePaths = copy(includePaths);
        excludePaths = copy(excludePaths);
        includeVerbs = copy(includeVerbs);
        excludeVerbs = copy(excludeVerbs);
        includeOperationIds = copy(includeOperationIds);
        excludeOperationIds = copy(excludeOperationIds);
        includeTags = copy(includeTags);
        excludeTags = copy(excludeTags);
    }

    /** True when the filter would keep every operation (no dimension constrains anything). */
    public boolean isEmpty() {
        return !excludeDeprecated
                && includePaths.isEmpty() && excludePaths.isEmpty()
                && includeVerbs.isEmpty() && excludeVerbs.isEmpty()
                && includeOperationIds.isEmpty() && excludeOperationIds.isEmpty()
                && includeTags.isEmpty() && excludeTags.isEmpty();
    }

    private static List<String> copy(List<String> in) {
        return in == null ? List.of() : List.copyOf(in);
    }
}
