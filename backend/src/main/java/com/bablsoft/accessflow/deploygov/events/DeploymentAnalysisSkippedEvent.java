package com.bablsoft.accessflow.deploygov.events;

import java.util.UUID;

/** No AI analysis ran — the pipeline has it disabled or no AI config. Routing proceeds risk-free. */
public record DeploymentAnalysisSkippedEvent(UUID deploymentRequestId, String reason) {
}
