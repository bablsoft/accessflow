package com.bablsoft.accessflow.apigov.api;

import java.time.Instant;
import java.util.UUID;

/**
 * A connector rate limit refused an execution (#972). The request stays {@code APPROVED} with
 * {@code throttled_until} stamped, and the run job retries it once that instant passes.
 */
public class ApiConnectorQuotaExceededException extends ApiGovException {

    private final UUID connectorId;
    private final ApiConnectorLimitKind limitKind;
    private final long retryAfterSeconds;
    private final Instant throttledUntil;

    public ApiConnectorQuotaExceededException(UUID connectorId, ApiConnectorLimitKind limitKind,
                                              long retryAfterSeconds, Instant throttledUntil) {
        super("Connector " + connectorId + " " + limitKind + " limit exceeded; retry after "
                + retryAfterSeconds + "s");
        this.connectorId = connectorId;
        this.limitKind = limitKind;
        this.retryAfterSeconds = retryAfterSeconds;
        this.throttledUntil = throttledUntil;
    }

    public UUID connectorId() {
        return connectorId;
    }

    public ApiConnectorLimitKind limitKind() {
        return limitKind;
    }

    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }

    public Instant throttledUntil() {
        return throttledUntil;
    }
}
