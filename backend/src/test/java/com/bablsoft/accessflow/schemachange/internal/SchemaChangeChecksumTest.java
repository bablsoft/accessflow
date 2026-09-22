package com.bablsoft.accessflow.schemachange.internal;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SchemaChangeChecksumTest {

    private static final String A = "CREATE TABLE t (id INT)";
    private static final String B = "ALTER TABLE t ADD c INT";

    @Test
    void sameOrderedStatementsProduceTheSameChecksum() {
        assertThat(SchemaChangeChecksum.of(List.of(A, B))).isEqualTo(SchemaChangeChecksum.of(List.of(A, B)));
    }

    @Test
    void reorderingChangesTheChecksum() {
        assertThat(SchemaChangeChecksum.of(List.of(A, B))).isNotEqualTo(SchemaChangeChecksum.of(List.of(B, A)));
    }

    @Test
    void checksumIsLowercaseSha256Hex() {
        var checksum = SchemaChangeChecksum.of(List.of("abc"));

        assertThat(checksum).hasSize(64).matches("[0-9a-f]{64}")
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }

    @Test
    void emptyListHasNoChecksum() {
        assertThat(SchemaChangeChecksum.of(List.of())).isNull();
        assertThat(SchemaChangeChecksum.of(null)).isNull();
    }

    @Test
    void normalizeTrimsAndStripsOneTrailingSemicolon() {
        assertThat(SchemaChangeChecksum.normalize("  " + A + " ;\n")).isEqualTo(A);
        assertThat(SchemaChangeChecksum.normalize(A + ";;")).isEqualTo(A + ";");
        assertThat(SchemaChangeChecksum.normalize(null)).isEmpty();
        assertThat(SchemaChangeChecksum.normalize("   ")).isEmpty();
    }

    @Test
    void whitespaceAndTrailingSemicolonDoNotChangeTheChecksumOnceNormalized() {
        var normalized = List.of(SchemaChangeChecksum.normalize("  " + A + ";  "), SchemaChangeChecksum.normalize(B));

        assertThat(SchemaChangeChecksum.of(normalized)).isEqualTo(SchemaChangeChecksum.of(List.of(A, B)));
    }
}
