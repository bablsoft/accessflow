package com.bablsoft.accessflow.apigov.internal.schema;

import com.bablsoft.accessflow.apigov.api.ApiOperation;
import com.bablsoft.accessflow.apigov.api.OperationFilter;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

/**
 * Applies an {@link OperationFilter} over a parsed operation list. Evaluated per operation as: keep
 * when every non-empty include dimension matches AND no exclude dimension matches — exclude wins.
 * Path and operation-id use glob matching; verb and tag use exact case-insensitive matching;
 * {@code excludeDeprecated} drops operations flagged {@code deprecated: true}.
 */
@Component
public class OperationFilterMatcher {

    public List<ApiOperation> apply(List<ApiOperation> operations, OperationFilter filter) {
        if (operations == null || operations.isEmpty()) {
            return List.of();
        }
        if (filter == null || filter.isEmpty()) {
            return List.copyOf(operations);
        }
        return operations.stream().filter(op -> keep(op, filter)).toList();
    }

    private boolean keep(ApiOperation op, OperationFilter f) {
        if (f.excludeDeprecated() && Boolean.TRUE.equals(op.deprecated())) {
            return false;
        }
        // Exclude wins across every dimension.
        if (globMatchesAny(f.excludePaths(), op.path())
                || globMatchesAny(f.excludeOperationIds(), op.operationId())
                || equalsAny(f.excludeVerbs(), op.verb())
                || tagsMatchAny(f.excludeTags(), op.tags())) {
            return false;
        }
        // Each non-empty include dimension must match.
        return includeAllows(f.includePaths(), op.path(), true)
                && includeAllows(f.includeOperationIds(), op.operationId(), true)
                && includeAllows(f.includeVerbs(), op.verb(), false)
                && includeTagsAllows(f.includeTags(), op.tags());
    }

    private boolean includeAllows(List<String> include, String candidate, boolean glob) {
        if (include.isEmpty()) {
            return true;
        }
        return glob ? globMatchesAny(include, candidate) : equalsAny(include, candidate);
    }

    private boolean includeTagsAllows(List<String> includeTags, List<String> opTags) {
        return includeTags.isEmpty() || tagsMatchAny(includeTags, opTags);
    }

    private boolean globMatchesAny(List<String> globs, String candidate) {
        return globs.stream().anyMatch(g -> GlobMatcher.matches(g, candidate));
    }

    private boolean equalsAny(List<String> values, String candidate) {
        if (candidate == null) {
            return false;
        }
        return values.stream().anyMatch(v -> v.trim().equalsIgnoreCase(candidate));
    }

    private boolean tagsMatchAny(List<String> filterTags, List<String> opTags) {
        if (filterTags.isEmpty() || opTags == null || opTags.isEmpty()) {
            return false;
        }
        for (var opTag : opTags) {
            if (opTag != null && filterTags.stream()
                    .anyMatch(t -> t.trim().toLowerCase(Locale.ROOT)
                            .equals(opTag.toLowerCase(Locale.ROOT)))) {
                return true;
            }
        }
        return false;
    }
}
