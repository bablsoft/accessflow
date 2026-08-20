package com.bablsoft.accessflow.audit.internal.codec;

/**
 * Syslog/CEF sink settings for dispatch. {@code tls} selects TLS over TCP, validated against the
 * system truststore — there is deliberately no skip-verify knob on a compliance pipe.
 */
public record SyslogCefSinkConfig(String host, int port, boolean tls) {
}
