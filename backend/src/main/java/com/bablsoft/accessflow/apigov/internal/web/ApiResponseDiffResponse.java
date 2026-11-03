package com.bablsoft.accessflow.apigov.internal.web;

import com.bablsoft.accessflow.apigov.api.ApiResponseDiffEntry;
import com.bablsoft.accessflow.apigov.api.ApiResponseDiffFormat;
import com.bablsoft.accessflow.apigov.api.ApiResponseDiffKind;
import com.bablsoft.accessflow.apigov.api.ApiResponseDiffView;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.UUID;

/**
 * Response body for {@code GET /api-requests/{id}/diff} (#887). {@code @JsonInclude(ALWAYS)}
 * overrides the global {@code non_null} default so the nullable deltas and an entry's absent side are
 * always present as {@code null}.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
record ApiResponseDiffResponse(
        UUID currentId,
        UUID previousId,
        ApiResponseDiffFormat format,
        Integer statusCodeCurrent,
        Integer statusCodePrevious,
        Long durationMsDelta,
        Long bytesDelta,
        int addedCount,
        int removedCount,
        int changedCount,
        boolean truncated,
        boolean eitherResponseTruncated,
        List<Entry> entries) {

    static ApiResponseDiffResponse from(ApiResponseDiffView view) {
        return new ApiResponseDiffResponse(view.currentId(), view.previousId(), view.format(),
                view.statusCodeCurrent(), view.statusCodePrevious(), view.durationMsDelta(), view.bytesDelta(),
                view.addedCount(), view.removedCount(), view.changedCount(), view.truncated(),
                view.eitherResponseTruncated(), view.entries().stream().map(Entry::from).toList());
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    record Entry(String path, ApiResponseDiffKind kind, String before, String after) {

        static Entry from(ApiResponseDiffEntry entry) {
            return new Entry(entry.path(), entry.kind(), entry.before(), entry.after());
        }
    }
}
