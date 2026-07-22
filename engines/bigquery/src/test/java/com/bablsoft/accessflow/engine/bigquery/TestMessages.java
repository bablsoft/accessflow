package com.bablsoft.accessflow.engine.bigquery;

import com.bablsoft.accessflow.core.api.EngineMessages;

import java.text.MessageFormat;
import java.util.Map;

/**
 * Test stand-ins for the host-provided {@link EngineMessages} (the host backs it with its Spring
 * {@code MessageSource}; the plugin tests stay Spring-free).
 */
final class TestMessages {

    private TestMessages() {
    }

    /** Resolves registered patterns with {@link MessageFormat}; unknown keys echo the key. */
    static EngineMessages of(Map<String, String> patterns) {
        return (key, args) -> {
            var pattern = patterns.get(key);
            if (pattern == null) {
                return key;
            }
            return args == null || args.length == 0 ? pattern : MessageFormat.format(pattern, args);
        };
    }

    /** Echoes the key (with formatted args appended) — enough to assert which key was raised. */
    static EngineMessages keyEcho() {
        return (key, args) -> args == null || args.length == 0
                ? key
                : key + " " + java.util.Arrays.toString(args);
    }
}
