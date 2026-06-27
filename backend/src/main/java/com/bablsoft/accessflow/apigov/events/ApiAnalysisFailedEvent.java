package com.bablsoft.accessflow.apigov.events;

import java.util.UUID;

/** Published when AI analysis fails; the request is forced to human review (fail-safe). */
public record ApiAnalysisFailedEvent(UUID apiRequestId, String reason) {
}
