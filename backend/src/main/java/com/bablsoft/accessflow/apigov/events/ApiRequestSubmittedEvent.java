package com.bablsoft.accessflow.apigov.events;

import java.util.UUID;

/** Published when an API request is persisted as PENDING_AI; triggers async AI analysis. */
public record ApiRequestSubmittedEvent(UUID apiRequestId) {
}
