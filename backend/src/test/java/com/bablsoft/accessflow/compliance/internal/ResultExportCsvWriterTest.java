package com.bablsoft.accessflow.compliance.internal;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

class ResultExportCsvWriterTest {

    private final ResultExportCsvWriter writer = new ResultExportCsvWriter();

    private String write(List<String> columns, List<List<Object>> rows, String header,
                         String footer) {
        return new String(writer.write(columns, rows, header, footer), UTF_8);
    }

    @Test
    void writesHeaderRowAndDataRowsWithCrlf() {
        var csv = write(List.of("id", "name"),
                List.of(List.of(1, "alice"), List.of(2, "bob")), null, null);

        assertThat(csv).isEqualTo("id,name\r\n1,alice\r\n2,bob\r\n");
    }

    @Test
    void escapesCommaQuoteAndNewline() {
        var csv = write(List.of("v"), List.of(
                List.of("a,b"),
                List.of("say \"hi\""),
                List.of("line1\nline2"),
                List.of("cr\rhere")), null, null);

        assertThat(csv).isEqualTo("v\r\n"
                + "\"a,b\"\r\n"
                + "\"say \"\"hi\"\"\"\r\n"
                + "\"line1\nline2\"\r\n"
                + "\"cr\rhere\"\r\n");
    }

    @Test
    void nullCellsRenderEmpty() {
        var row = new ArrayList<Object>(Arrays.asList(1, null));
        var csv = write(List.of("id", "name"), List.of(row), null, null);

        assertThat(csv).isEqualTo("id,name\r\n1,\r\n");
    }

    @Test
    void watermarkHeaderAndFooterAreSingleCellFirstAndLastRecords() {
        var csv = write(List.of("id"), List.of(List.of(1)),
                "AccessFlow export | a@x.com", "AccessFlow export end | 1 rows");

        assertThat(csv).isEqualTo("AccessFlow export | a@x.com\r\n"
                + "id\r\n"
                + "1\r\n"
                + "AccessFlow export end | 1 rows\r\n");
    }

    @Test
    void watermarkContainingCommaIsQuotedAsOneCell() {
        var csv = write(List.of("id"), List.of(),
                "exported, watch out", null);

        assertThat(csv).startsWith("\"exported, watch out\"\r\nid\r\n");
    }

    @Test
    void noWatermarkMeansNoExtraRecords() {
        var csv = write(List.of("id"), List.of(List.of(7)), null, null);

        assertThat(csv).isEqualTo("id\r\n7\r\n");
        assertThat(csv).doesNotContain("AccessFlow export");
    }

    @Test
    void emptyRowsProduceHeaderOnly() {
        var csv = write(List.of("a", "b"), List.of(), null, null);

        assertThat(csv).isEqualTo("a,b\r\n");
    }
}
