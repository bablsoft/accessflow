package com.bablsoft.accessflow.deploygov.api;

import java.util.UUID;

/**
 * The freeze window in effect for a deployment target (#877). {@code behavior} is what the caller
 * must honour: {@link FreezeBehavior#HOLD} keeps the target not-releasable, {@link
 * FreezeBehavior#REJECT} refuses outright. A window whose stored definition cannot be evaluated
 * surfaces here as an active {@code HOLD}. {@code reason} is the admin's free text, possibly null.
 */
public record ActiveDeploymentFreezeView(UUID windowId, FreezeBehavior behavior, String reason) {
}
