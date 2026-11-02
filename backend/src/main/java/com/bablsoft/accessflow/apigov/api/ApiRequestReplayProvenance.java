package com.bablsoft.accessflow.apigov.api;

import java.util.UUID;

/** Marks a submission as a replay of a recorded API request (#886); carried into audit metadata only. */
public record ApiRequestReplayProvenance(UUID originalApiRequestId, int droppedVariableOverrideCount) {
}
