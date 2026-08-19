package com.bablsoft.accessflow.compliance.api;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ResultExportWatermarkTest {

    @Test
    void headerProducesExactTemplate() {
        var id = UUID.fromString("11111111-2222-3333-4444-555555555555");

        var header = ResultExportWatermark.header("alice@example.com",
                Instant.parse("2026-07-02T09:15:30Z"), id);

        assertThat(header).isEqualTo("AccessFlow export | alice@example.com | "
                + "2026-07-02T09:15:30Z | query 11111111-2222-3333-4444-555555555555");
    }

    @Test
    void headerTruncatesTimestampToSeconds() {
        var id = UUID.fromString("11111111-2222-3333-4444-555555555555");

        var header = ResultExportWatermark.header("alice@example.com",
                Instant.parse("2026-07-02T09:15:30.987654321Z"), id);

        assertThat(header).contains("| 2026-07-02T09:15:30Z |");
        assertThat(header).doesNotContain(".987");
    }

    @Test
    void footerWithoutCapListsRowCountOnly() {
        assertThat(ResultExportWatermark.footer(42, null))
                .isEqualTo("AccessFlow export end | 42 rows");
    }

    @Test
    void footerWithCapAppendsCappedSuffix() {
        assertThat(ResultExportWatermark.footer(10, 10))
                .isEqualTo("AccessFlow export end | 10 rows | capped at 10");
    }
}
