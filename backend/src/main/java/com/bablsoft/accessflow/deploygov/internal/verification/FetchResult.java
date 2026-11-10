package com.bablsoft.accessflow.deploygov.internal.verification;

/** What one verification fetch produced: an HTTP response, or the reason there is none. */
sealed interface FetchResult permits FetchResult.Response, FetchResult.Failure {

    /** An HTTP response. {@code body} is capped; {@code truncated} says whether it was cut. */
    record Response(int status, String body, boolean truncated) implements FetchResult {
    }

    /** No response — refused by the target policy, timed out, or failed in transport. */
    record Failure(String reason) implements FetchResult {
    }
}
