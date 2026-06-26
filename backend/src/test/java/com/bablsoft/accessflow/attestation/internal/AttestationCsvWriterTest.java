package com.bablsoft.accessflow.attestation.internal;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AttestationCsvWriterTest {

    @Test
    void writesPlainCellsCommaSeparatedAndCrlfTerminated() {
        var sb = new StringBuilder();
        AttestationCsvWriter.appendRow(sb, "a", "b", "c");
        assertThat(sb.toString()).isEqualTo("a,b,c\r\n");
    }

    @Test
    void quotesCellsContainingCommaQuoteOrNewline() {
        var sb = new StringBuilder();
        AttestationCsvWriter.appendRow(sb, "has,comma", "has\"quote", "has\nnewline");
        assertThat(sb.toString())
                .isEqualTo("\"has,comma\",\"has\"\"quote\",\"has\nnewline\"\r\n");
    }

    @Test
    void rendersNullCellAsEmpty() {
        var sb = new StringBuilder();
        AttestationCsvWriter.appendRow(sb, "x", null, "y");
        assertThat(sb.toString()).isEqualTo("x,,y\r\n");
    }
}
