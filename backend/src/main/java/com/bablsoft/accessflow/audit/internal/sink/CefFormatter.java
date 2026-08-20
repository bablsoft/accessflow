package com.bablsoft.accessflow.audit.internal.sink;

import org.springframework.stereotype.Component;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * Formats an export event as an RFC 5424 syslog frame carrying a CEF:0 message. The CEF
 * severity mapping is a deliberate v1 heuristic — 7 for destructive/exceptional actions
 * (BREAK_GLASS / DELETED / REJECTED), 5 for everything else — documented in docs/05-backend.md
 * so operators do not mistake it for a tuned taxonomy.
 */
@Component
public class CefFormatter {

    static final String VENDOR = "AccessFlow";
    static final String PRODUCT = "AccessFlow";
    static final String CEF_VERSION = "1.0";
    static final String HOSTNAME = "accessflow";
    static final String APP_NAME = "accessflow";

    /** RFC 5424 facility 13 (log audit). */
    private static final int FACILITY = 13;
    private static final int SYSLOG_SEVERITY_NOTICE = 5;
    private static final int SYSLOG_SEVERITY_WARNING = 4;
    private static final int HIGH_CEF_SEVERITY = 7;
    private static final int DEFAULT_CEF_SEVERITY = 5;

    private static final DateTimeFormatter RFC5424_TIMESTAMP =
            DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    /** One complete syslog message (without transport framing). */
    public String format(AuditExportEvent event) {
        int cefSeverity = cefSeverity(event.action());
        int syslogSeverity = cefSeverity >= HIGH_CEF_SEVERITY
                ? SYSLOG_SEVERITY_WARNING : SYSLOG_SEVERITY_NOTICE;
        int pri = FACILITY * 8 + syslogSeverity;
        var timestamp = RFC5424_TIMESTAMP.format(event.createdAt().atOffset(ZoneOffset.UTC));
        return "<" + pri + ">1 " + timestamp + " " + HOSTNAME + " " + APP_NAME + " - - - "
                + cef(event, cefSeverity);
    }

    private String cef(AuditExportEvent event, int severity) {
        var sb = new StringBuilder("CEF:0|")
                .append(VENDOR).append('|')
                .append(PRODUCT).append('|')
                .append(CEF_VERSION).append('|')
                .append(escapeHeader(event.action())).append('|')
                .append(escapeHeader(event.action())).append('|')
                .append(severity).append('|');
        extension(sb, "externalId", event.id() == null ? null : event.id().toString());
        extension(sb, "rt", String.valueOf(event.createdAt().toEpochMilli()));
        extension(sb, "suser", event.actorId() == null ? null : event.actorId().toString());
        extension(sb, "src", event.ipAddress());
        labeled(sb, "cs1", "resource_type", event.resourceType());
        labeled(sb, "cs2", "resource_id",
                event.resourceId() == null ? null : event.resourceId().toString());
        labeled(sb, "cs3", "current_hash", event.currentHash());
        labeled(sb, "cs4", "previous_hash", event.previousHash());
        return sb.toString().stripTrailing();
    }

    static int cefSeverity(String action) {
        if (action == null) {
            return DEFAULT_CEF_SEVERITY;
        }
        if (action.contains("BREAK_GLASS") || action.contains("DELETED")
                || action.contains("REJECTED")) {
            return HIGH_CEF_SEVERITY;
        }
        return DEFAULT_CEF_SEVERITY;
    }

    private static void labeled(StringBuilder sb, String key, String label, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        extension(sb, key + "Label", label);
        extension(sb, key, value);
    }

    private static void extension(StringBuilder sb, String key, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        sb.append(key).append('=').append(escapeExtension(value)).append(' ');
    }

    /** CEF header fields escape backslash and pipe. */
    static String escapeHeader(String value) {
        return value.replace("\\", "\\\\").replace("|", "\\|");
    }

    /** CEF extension values escape backslash, equals, and newlines. */
    static String escapeExtension(String value) {
        return value.replace("\\", "\\\\").replace("=", "\\=")
                .replace("\n", "\\n").replace("\r", "\\r");
    }
}
