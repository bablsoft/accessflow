package com.bablsoft.accessflow.deploygov.api;

import com.bablsoft.accessflow.core.api.SubmissionReason;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Trigger a governed deployment (#691). {@code environment} is the environment <em>name</em> within
 * the pipeline — a CI job knows {@code production}, not a UUID. {@code externalRunId} is the
 * provider-side run identifier; supplying it makes the trigger idempotent, so a replayed CI job
 * returns the existing request instead of queueing a second approval.
 *
 * <p>{@code admin} tells the service the caller holds administrative request oversight and may
 * trigger without a per-pipeline grant; the controller computes it from the caller's permissions.
 */
public record SubmitDeploymentRequestCommand(
        UUID pipelineId,
        String environment,
        UUID organizationId,
        UUID submitterUserId,
        boolean admin,
        String version,
        String commitSha,
        String artifactRef,
        String runUrl,
        String externalRunId,
        Map<String, Object> metadata,
        String justification,
        Instant scheduledFor,
        SubmissionReason submissionReason,
        String submittedIp) {

    public SubmitDeploymentRequestCommand {
        // A defensive copy that tolerates null values — the metadata is CI-authored JSON, and
        // Map.copyOf would turn a `{"changelog": null}` payload into a 500.
        metadata = metadata == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
    }
}
