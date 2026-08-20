package com.bablsoft.accessflow.audit.internal.sink;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CefFormatterTest {

    private static final Instant CREATED_AT = Instant.parse("2026-08-19T10:15:30.123456Z");
    private static final UUID EVENT_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final UUID ACTOR_ID = UUID.fromString("99999999-8888-7777-6666-555555555555");
    private static final UUID RESOURCE_ID = UUID.fromString("12121212-3434-5656-7878-909090909090");

    private final CefFormatter formatter = new CefFormatter();

    private AuditExportEvent event(String action) {
        return new AuditExportEvent(EVENT_ID, UUID.randomUUID(), ACTOR_ID, action,
                "query_request", RESOURCE_ID, "{}", "203.0.113.5", "ua/1", CREATED_AT,
                "aa11", "bb22");
    }

    @Test
    void formatsRfc5424FrameWithCefHeader() {
        var message = formatter.format(event("QUERY_SUBMITTED"));

        // Facility 13 (log audit) * 8 + severity NOTICE(5) = 109.
        assertThat(message).startsWith("<109>1 2026-08-19T10:15:30.123456Z accessflow accessflow - - - ");
        assertThat(message).contains(
                "CEF:0|AccessFlow|AccessFlow|1.0|QUERY_SUBMITTED|QUERY_SUBMITTED|5|");
        assertThat(message).contains("externalId=" + EVENT_ID);
        assertThat(message).contains("rt=" + CREATED_AT.toEpochMilli());
        assertThat(message).contains("suser=" + ACTOR_ID);
        assertThat(message).contains("src=203.0.113.5");
        assertThat(message).contains("cs1Label=resource_type cs1=query_request");
        assertThat(message).contains("cs2Label=resource_id cs2=" + RESOURCE_ID);
        assertThat(message).contains("cs3Label=current_hash cs3=bb22");
        assertThat(message).contains("cs4Label=previous_hash cs4=aa11");
        assertThat(message).doesNotEndWith(" ");
    }

    @Test
    void severityHeuristicFlagsDestructiveActions() {
        assertThat(CefFormatter.cefSeverity("QUERY_BREAK_GLASS_EXECUTED")).isEqualTo(7);
        assertThat(CefFormatter.cefSeverity("DATASOURCE_DELETED")).isEqualTo(7);
        assertThat(CefFormatter.cefSeverity("QUERY_REJECTED")).isEqualTo(7);
        assertThat(CefFormatter.cefSeverity("QUERY_SUBMITTED")).isEqualTo(5);
        assertThat(CefFormatter.cefSeverity(null)).isEqualTo(5);
    }

    @Test
    void highSeverityMapsToWarningPri() {
        var message = formatter.format(event("QUERY_REJECTED"));

        // Facility 13 * 8 + severity WARNING(4) = 108.
        assertThat(message).startsWith("<108>1 ");
        assertThat(message).contains("|QUERY_REJECTED|QUERY_REJECTED|7|");
    }

    @Test
    void omitsNullAndBlankFields() {
        var sparse = new AuditExportEvent(EVENT_ID, UUID.randomUUID(), null, "QUERY_SUBMITTED",
                " ", null, "{}", null, null, CREATED_AT, null, null);

        var message = formatter.format(sparse);

        assertThat(message).doesNotContain("suser=");
        assertThat(message).doesNotContain("src=");
        assertThat(message).doesNotContain("cs1Label=");
        assertThat(message).doesNotContain("cs2Label=");
        assertThat(message).doesNotContain("cs3Label=");
        assertThat(message).doesNotContain("cs4Label=");
        assertThat(message).contains("externalId=" + EVENT_ID);
    }

    @Test
    void escapesHeaderPipesAndBackslashes() {
        var message = formatter.format(event("WEIRD|ACT\\ION"));

        assertThat(message).contains("|WEIRD\\|ACT\\\\ION|WEIRD\\|ACT\\\\ION|");
    }

    @Test
    void escapesExtensionValues() {
        var withNasty = new AuditExportEvent(EVENT_ID, UUID.randomUUID(), null,
                "QUERY_SUBMITTED", "a=b\\c\nd\re", null, "{}", null, null, CREATED_AT,
                null, null);

        var message = formatter.format(withNasty);

        assertThat(message).contains("cs1=a\\=b\\\\c\\nd\\re");
    }

    @Test
    void escapeHelpersCoverEachCharacter() {
        assertThat(CefFormatter.escapeHeader("a|b\\c")).isEqualTo("a\\|b\\\\c");
        assertThat(CefFormatter.escapeExtension("a=b\\c\nd\re"))
                .isEqualTo("a\\=b\\\\c\\nd\\re");
    }
}
