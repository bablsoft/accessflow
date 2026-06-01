package com.bablsoft.accessflow.proxy.internal;

import com.bablsoft.accessflow.core.api.MaskingStrategy;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ColumnMaskerTest {

    @Test
    void nullInputStaysNullForEveryStrategy() {
        for (var strategy : MaskingStrategy.values()) {
            assertThat(ColumnMasker.apply(strategy, null, Map.of())).isNull();
        }
    }

    @Test
    void fullReplacesWholeValue() {
        assertThat(ColumnMasker.apply(MaskingStrategy.FULL, "4111111111111111", Map.of()))
                .isEqualTo("***");
    }

    @Test
    void partialKeepsLastNCharactersByDefault() {
        assertThat(ColumnMasker.apply(MaskingStrategy.PARTIAL, "4111111111111234", Map.of()))
                .isEqualTo("************1234");
    }

    @Test
    void partialHonoursVisibleSuffixParam() {
        assertThat(ColumnMasker.apply(MaskingStrategy.PARTIAL, "abcdef",
                Map.of("visible_suffix", "2"))).isEqualTo("****ef");
    }

    @Test
    void partialMasksEverythingWhenValueNoLongerThanWindow() {
        assertThat(ColumnMasker.apply(MaskingStrategy.PARTIAL, "abc",
                Map.of("visible_suffix", "4"))).isEqualTo("***");
        assertThat(ColumnMasker.apply(MaskingStrategy.PARTIAL, "1234",
                Map.of("visible_suffix", "4"))).isEqualTo("****");
    }

    @Test
    void partialFallsBackToDefaultOnInvalidParam() {
        assertThat(ColumnMasker.apply(MaskingStrategy.PARTIAL, "abcdef",
                Map.of("visible_suffix", "not-a-number"))).isEqualTo("**cdef");
        assertThat(ColumnMasker.apply(MaskingStrategy.PARTIAL, "abcdef",
                Map.of("visible_suffix", "  "))).isEqualTo("**cdef");
    }

    @Test
    void hashIsDeterministicSha256Hex() {
        var first = ColumnMasker.apply(MaskingStrategy.HASH, "secret", Map.of());
        var second = ColumnMasker.apply(MaskingStrategy.HASH, "secret", Map.of());
        assertThat(first).isEqualTo(second);
        assertThat(first).hasSize(64).matches("[0-9a-f]{64}");
        // Known SHA-256 of "secret".
        assertThat(first).isEqualTo(
                "2bb80d537b1da3e38bd30361aa855686bde0eacd7162fef6a25fe97bf527a25b");
    }

    @Test
    void emailPreservesFirstCharAndDomain() {
        assertThat(ColumnMasker.apply(MaskingStrategy.EMAIL, "jane.doe@example.com", Map.of()))
                .isEqualTo("j***@example.com");
    }

    @Test
    void emailFallsBackToFullMaskWhenNotEmailShaped() {
        assertThat(ColumnMasker.apply(MaskingStrategy.EMAIL, "not-an-email", Map.of()))
                .isEqualTo("***");
        assertThat(ColumnMasker.apply(MaskingStrategy.EMAIL, "@nolocal.com", Map.of()))
                .isEqualTo("***");
        assertThat(ColumnMasker.apply(MaskingStrategy.EMAIL, "nodomain@", Map.of()))
                .isEqualTo("***");
    }

    @Test
    void formatPreservingKeepsShapeReplacingDigitsAndLetters() {
        assertThat(ColumnMasker.apply(MaskingStrategy.FORMAT_PRESERVING, "555-12-3456", Map.of()))
                .isEqualTo("***-**-****");
        assertThat(ColumnMasker.apply(MaskingStrategy.FORMAT_PRESERVING, "AB-12 cd", Map.of()))
                .isEqualTo("xx-** xx");
    }
}
