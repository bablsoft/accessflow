package com.bablsoft.accessflow.access.internal;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GrantUsageCsvWriterTest {

    @Test
    void writesCommaSeparatedCrlfTerminatedRows() {
        var sb = new StringBuilder();
        GrantUsageCsvWriter.appendRow(sb, "a", "b");
        GrantUsageCsvWriter.appendRow(sb, "c", "d");
        assertThat(sb).hasToString("a,b\r\nc,d\r\n");
    }

    @Test
    void quotesCellsContainingSeparatorsQuotesOrNewlines() {
        var sb = new StringBuilder();
        GrantUsageCsvWriter.appendRow(sb, "a,b", "say \"hi\"", "line1\nline2", "cr\rlf");
        assertThat(sb).hasToString("\"a,b\",\"say \"\"hi\"\"\",\"line1\nline2\",\"cr\rlf\"\r\n");
    }

    @Test
    void rendersNullAsAnEmptyCell() {
        var sb = new StringBuilder();
        GrantUsageCsvWriter.appendRow(sb, "a", null, "c");
        assertThat(sb).hasToString("a,,c\r\n");
    }

    @Test
    void writesASingleCellRowWithoutASeparator() {
        var sb = new StringBuilder();
        GrantUsageCsvWriter.appendRow(sb, "only");
        assertThat(sb).hasToString("only\r\n");
    }
}
