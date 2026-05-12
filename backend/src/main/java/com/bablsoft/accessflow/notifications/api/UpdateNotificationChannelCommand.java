package com.bablsoft.accessflow.notifications.api;

import java.util.Map;

/**
 * Partial-update command. Any null field is left untouched. Sensitive values shown as the
 * masked placeholder are interpreted as "keep the existing ciphertext".
 */
public record UpdateNotificationChannelCommand(
        String name,
        Map<String, Object> config,
        Boolean active) {
}
