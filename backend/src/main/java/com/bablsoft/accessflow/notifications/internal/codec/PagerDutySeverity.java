package com.bablsoft.accessflow.notifications.internal.codec;

import com.bablsoft.accessflow.notifications.api.NotificationChannelConfigException;

import java.util.Locale;

/**
 * PagerDuty Events API v2 {@code payload.severity} value. The wire representation is lowercase.
 */
public enum PagerDutySeverity {
    CRITICAL,
    ERROR,
    WARNING,
    INFO;

    public String wireValue() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static PagerDutySeverity fromWire(String value) {
        if (value == null || value.isBlank()) {
            throw new NotificationChannelConfigException(
                    "PagerDuty channel config requires '" + ChannelConfigCodec.KEY_DEFAULT_SEVERITY + "'");
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new NotificationChannelConfigException(
                    "Config key '" + ChannelConfigCodec.KEY_DEFAULT_SEVERITY
                            + "' must be one of critical, error, warning, info", ex);
        }
    }
}
