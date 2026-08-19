package com.bablsoft.accessflow.compliance.internal;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class ResultExportPdfWriterTest {

    private final ResultExportPdfWriter writer = new ResultExportPdfWriter();
    private final UUID queryId = UUID.randomUUID();
    private final Instant t = Instant.parse("2026-07-02T09:00:00Z");

    private String extractText(byte[] pdf) throws IOException {
        try (var doc = Loader.loadPDF(pdf)) {
            return new PDFTextStripper().getText(doc);
        }
    }

    @Test
    void rendersParseablePdfWithHeadersAndCells() throws IOException {
        var pdf = writer.write(queryId, List.of("id", "email"),
                List.of(List.of(1, "alice@example.com"), List.of(2, "bob@example.com")),
                null, null, "exporter@example.com", t, false);

        assertThat(new String(pdf, 0, 5, US_ASCII)).isEqualTo("%PDF-");
        var text = extractText(pdf);
        assertThat(text).contains("Query Results Export");
        assertThat(text).contains("Query request: " + queryId);
        assertThat(text).contains("alice@example.com");
        assertThat(text).contains("email");
    }

    @Test
    void stampsDocumentMetadata() throws IOException {
        var watermark = "AccessFlow export | exporter@example.com | " + t + " | query " + queryId;
        var pdf = writer.write(queryId, List.of("id"), List.of(List.of(1)),
                watermark, "AccessFlow export end | 1 rows", "exporter@example.com", t, false);

        try (var doc = Loader.loadPDF(pdf)) {
            var info = doc.getDocumentInformation();
            assertThat(info.getAuthor()).isEqualTo("exporter@example.com");
            assertThat(info.getSubject()).isEqualTo("query " + queryId);
            assertThat(info.getKeywords()).isEqualTo(watermark);
            assertThat(info.getCreator()).isEqualTo("AccessFlow");
            assertThat(info.getCustomMetadataValue("AccessFlow-Generated-At"))
                    .isEqualTo(t.toString());
        }
        var text = extractText(pdf);
        assertThat(text).contains("AccessFlow export | exporter@example.com");
        assertThat(text).contains("AccessFlow export end | 1 rows");
    }

    @Test
    void sanitizesNonAsciiCellsWithoutThrowing() throws IOException {
        assertThatCode(() -> writer.write(queryId, List.of("name"),
                List.of(List.of("héllo → 世界"), List.of("bell")),
                null, null, "exporter@example.com", t, false))
                .doesNotThrowAnyException();

        var pdf = writer.write(queryId, List.of("name"), List.of(List.of("héllo")),
                null, null, "exporter@example.com", t, false);
        assertThat(extractText(pdf)).contains("h?llo");
    }

    @Test
    void rendersEmptyRowsWithNotice() throws IOException {
        var pdf = writer.write(queryId, List.of("id", "name"), List.of(),
                null, null, "exporter@example.com", t, true);

        var text = extractText(pdf);
        assertThat(text).contains("No rows.");
        assertThat(text).contains("truncated");
    }

    @Test
    void paginatesAcrossManyRows() throws IOException {
        var rows = new ArrayList<List<Object>>();
        for (int i = 0; i < 200; i++) {
            rows.add(List.of(i, "value-" + i));
        }
        var pdf = writer.write(queryId, List.of("id", "value"), rows, null, null,
                "exporter@example.com", t, false);

        try (var doc = Loader.loadPDF(pdf)) {
            assertThat(doc.getNumberOfPages()).isGreaterThan(1);
        }
    }

    @Test
    void shortRowsArePaddedToColumnCount() throws IOException {
        var pdf = writer.write(queryId, List.of("a", "b", "c"),
                List.of(List.of("only-one")), null, null, "exporter@example.com", t, false);

        assertThat(extractText(pdf)).contains("only-one");
    }
}
