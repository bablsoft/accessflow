package com.bablsoft.accessflow.schemachange.api;

import com.bablsoft.accessflow.deploygov.api.FreezeBehavior;

import java.util.UUID;

/**
 * One environment of a change set's ladder (#883). {@code latestPromotion} is the newest promotion
 * to this environment (without its snapshot), or null. {@code blocker} is set exactly when
 * {@code state} is {@link SchemaChangeLadderRungState#BLOCKED}; the {@code blocking*} fields carry
 * the unapplied lower rung for {@link SchemaChangeLadderBlocker#LOWER_ENVIRONMENT_NOT_APPLIED} and
 * the {@code freeze*} fields the active window for {@link SchemaChangeLadderBlocker#FREEZE_ACTIVE}.
 */
public record SchemaChangeLadderRungView(
        UUID environmentId,
        String environmentName,
        int sortOrder,
        UUID datasourceId,
        SchemaChangePromotionView latestPromotion,
        SchemaChangeLadderRungState state,
        SchemaChangeLadderBlocker blocker,
        UUID blockingEnvironmentId,
        String blockingEnvironmentName,
        UUID freezeWindowId,
        FreezeBehavior freezeBehavior,
        String freezeReason) {
}
