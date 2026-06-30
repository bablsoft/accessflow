package com.bablsoft.accessflow.apigov.internal;

import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * W3C Trace Context (https://www.w3.org/TR/trace-context/) id generation and {@code traceparent}
 * formatting. A trace id is 16 random bytes (32 lowercase hex), a span id is 8 random bytes (16
 * lowercase hex), and the {@code traceparent} value is {@code 00-<trace>-<span>-01} (version 00,
 * sampled flag 01).
 */
final class TraceContext {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final HexFormat HEX = HexFormat.of();

    private TraceContext() {
    }

    static String newTraceId() {
        return randomHex(16);
    }

    static String newSpanId() {
        return randomHex(8);
    }

    static String traceparent(String traceId, String spanId) {
        return "00-" + traceId + "-" + spanId + "-01";
    }

    private static String randomHex(int bytes) {
        var buf = new byte[bytes];
        RANDOM.nextBytes(buf);
        return HEX.formatHex(buf);
    }
}
