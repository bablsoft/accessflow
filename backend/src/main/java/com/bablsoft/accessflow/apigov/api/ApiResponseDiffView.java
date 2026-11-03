package com.bablsoft.accessflow.apigov.api;

import java.util.List;
import java.util.UUID;

/** Deltas are {@code current - previous}, null when either side lacks the number (#887). */
public record ApiResponseDiffView(UUID currentId, UUID previousId, ApiResponseDiffFormat format,
                                  Integer statusCodeCurrent, Integer statusCodePrevious,
                                  Long durationMsDelta, Long bytesDelta,
                                  int addedCount, int removedCount, int changedCount,
                                  boolean truncated, boolean eitherResponseTruncated,
                                  List<ApiResponseDiffEntry> entries) {
}
