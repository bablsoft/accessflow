package com.bablsoft.accessflow.core.api;

/**
 * What a bytes-scanned cap (#941) does when the query has no bytes estimate to compare against —
 * the estimate failed, the statement shape has no plan (DDL), or the engine returned no bytes
 * figure. Never "allow silently": {@link #REQUIRE_REVIEW} suppresses every automatic approval so a
 * person decides, {@link #REJECT} refuses the query outright.
 */
public enum BytesCapMissingEstimateAction {
    REQUIRE_REVIEW,
    REJECT
}
