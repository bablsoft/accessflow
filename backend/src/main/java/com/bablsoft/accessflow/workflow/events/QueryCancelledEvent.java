package com.bablsoft.accessflow.workflow.events;

import java.util.UUID;

/**
 * Published when a submitter cancels their own query while it is still in {@code PENDING_AI}
 * or {@code PENDING_REVIEW}. Notifications is the planned consumer; audit writes are recorded
 * synchronously from the controller so the live request's IP and User-Agent are preserved.
 */
public record QueryCancelledEvent(UUID queryRequestId, UUID submitterUserId) {
}
