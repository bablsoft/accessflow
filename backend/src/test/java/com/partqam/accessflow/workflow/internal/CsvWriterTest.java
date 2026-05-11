package com.partqam.accessflow.workflow.internal;

import org.junit.jupiter.api.Test;

import java.io.StringWriter;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CsvWriterTest {

    @Test
    void writesPlainFieldsWithoutQuoting() throws Exception {
        var sw = new StringWriter();
        CsvWriter.writeRow(sw, List.of("a", "b", "c"));
        assertThat(sw.toString()).isEqualTo("a,b,c\r\n");
    }

    @Test
    void writesEmptyAndNullFieldsAsEmpty() throws Exception {
        var sw = new StringWriter();
        // List.of forbids null elements; use Arrays.asList to verify the null branch.
        CsvWriter.writeRow(sw, Arrays.asList(null, "", "x"));
        assertThat(sw.toString()).isEqualTo(",,x\r\n");
    }

    @Test
    void quotesAndEscapesCommas() throws Exception {
        var sw = new StringWriter();
        CsvWriter.writeRow(sw, List.of("hello, world", "plain"));
        assertThat(sw.toString()).isEqualTo("\"hello, world\",plain\r\n");
    }

    @Test
    void quotesAndDoublesEmbeddedQuotes() throws Exception {
        var sw = new StringWriter();
        CsvWriter.writeRow(sw, List.of("she said \"hi\"", "ok"));
        assertThat(sw.toString()).isEqualTo("\"she said \"\"hi\"\"\",ok\r\n");
    }

    @Test
    void quotesNewlinesAndCarriageReturns() throws Exception {
        var sw = new StringWriter();
        CsvWriter.writeRow(sw, List.of("line1\nline2", "with\rcr"));
        assertThat(sw.toString()).isEqualTo("\"line1\nline2\",\"with\rcr\"\r\n");
    }

    @Test
    void mixedRowQuotesOnlyFieldsThatNeedIt() throws Exception {
        var sw = new StringWriter();
        CsvWriter.writeRow(sw, List.of("clean", "needs, quoting", "also\"needs", "fine"));
        assertThat(sw.toString())
                .isEqualTo("clean,\"needs, quoting\",\"also\"\"needs\",fine\r\n");
    }

    @Test
    void emptyRowEmitsLineTerminatorOnly() throws Exception {
        var sw = new StringWriter();
        CsvWriter.writeRow(sw, List.of());
        assertThat(sw.toString()).isEqualTo("\r\n");
    }
}
