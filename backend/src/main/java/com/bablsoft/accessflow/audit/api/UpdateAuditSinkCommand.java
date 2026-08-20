package com.bablsoft.accessflow.audit.api;

import java.util.Map;

/**
 * Partial-update command. Any null field is left untouched; the sink type is immutable.
 * Sensitive config values shown as the masked placeholder are interpreted as "keep the
 * existing ciphertext".
 */
public record UpdateAuditSinkCommand(
        String name,
        Map<String, Object> config,
        Boolean enabled) {
}
