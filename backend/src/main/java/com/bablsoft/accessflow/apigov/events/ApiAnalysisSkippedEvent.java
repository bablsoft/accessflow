package com.bablsoft.accessflow.apigov.events;

import java.util.UUID;

/** Published when AI analysis is skipped (connector ai_analysis_enabled=false). */
public record ApiAnalysisSkippedEvent(UUID apiRequestId, String reason) {
}
