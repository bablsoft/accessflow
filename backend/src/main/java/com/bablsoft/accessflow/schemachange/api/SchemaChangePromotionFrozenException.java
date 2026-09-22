package com.bablsoft.accessflow.schemachange.api;

import com.bablsoft.accessflow.deploygov.api.FreezeBehavior;

import java.util.UUID;

/**
 * A deploygov freeze window is in effect for the target environment (#880). Both behaviours
 * refuse a promotion — {@code HOLD} is also what an unevaluable window degrades to, so the
 * refusal fails closed. Mapped to HTTP 409.
 */
public final class SchemaChangePromotionFrozenException extends SchemaChangeException {

    private final UUID environmentId;
    private final UUID freezeWindowId;
    private final FreezeBehavior behavior;
    private final String reason;

    public SchemaChangePromotionFrozenException(UUID environmentId, UUID freezeWindowId, FreezeBehavior behavior,
                                                String reason) {
        super("Deployment environment " + environmentId + " is frozen (" + behavior + ") by window " + freezeWindowId);
        this.environmentId = environmentId;
        this.freezeWindowId = freezeWindowId;
        this.behavior = behavior;
        this.reason = reason;
    }

    public UUID environmentId() {
        return environmentId;
    }

    public UUID freezeWindowId() {
        return freezeWindowId;
    }

    public FreezeBehavior behavior() {
        return behavior;
    }

    public String reason() {
        return reason;
    }
}
