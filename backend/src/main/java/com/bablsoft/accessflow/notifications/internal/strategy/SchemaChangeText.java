package com.bablsoft.accessflow.notifications.internal.strategy;

/**
 * Shared rendering for the #882 schema-change events. A failed statement's error comes straight
 * from the customer database driver and can run to kilobytes; chat fields have hard size limits
 * (Discord 1024, Slack 2000), so every channel shows the same bounded prefix.
 */
final class SchemaChangeText {

    static final int ERROR_MAX_LENGTH = 500;

    private SchemaChangeText() {
    }

    static String truncate(String error) {
        if (error == null || error.length() <= ERROR_MAX_LENGTH) {
            return error;
        }
        return error.substring(0, ERROR_MAX_LENGTH - 1) + "…";
    }
}
