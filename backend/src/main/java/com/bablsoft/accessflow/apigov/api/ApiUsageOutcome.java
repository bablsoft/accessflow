package com.bablsoft.accessflow.apigov.api;

/** How one governed call ended, for the daily usage rollup (#973). */
public enum ApiUsageOutcome {
    /** The upstream call ran and returned a response; accrues the connector's price. */
    EXECUTED,
    /** The call was attempted and failed; still accrues the price — the attempt is what is billed. */
    FAILED,
    /**
     * A connector rate limit deferred the call; counted, never priced. Counted per refused attempt,
     * so a request re-throttled on each retry counts every time.
     */
    THROTTLED
}
