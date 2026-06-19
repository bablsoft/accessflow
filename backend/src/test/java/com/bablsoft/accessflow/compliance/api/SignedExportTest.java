package com.bablsoft.accessflow.compliance.api;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SignedExportTest {

    @Test
    void nullContentBecomesEmptyArray() {
        var export = new SignedExport(null, "f.pdf", "application/pdf", "sha", "sig", "alg", false);

        assertThat(export.content()).isEmpty();
    }

    @Test
    void contentIsDefensivelyCopiedInAndOut() {
        var original = new byte[]{1, 2, 3};
        var export = new SignedExport(original, "f.csv", "text/csv", "sha", "sig", "alg", true);

        original[0] = 9; // mutate caller's array
        assertThat(export.content()).containsExactly(1, 2, 3);

        var got = export.content();
        got[0] = 7; // mutate returned array
        assertThat(export.content()).containsExactly(1, 2, 3);
    }
}
