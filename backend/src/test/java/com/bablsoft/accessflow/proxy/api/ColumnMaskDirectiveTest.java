package com.bablsoft.accessflow.proxy.api;

import com.bablsoft.accessflow.core.api.MaskingStrategy;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ColumnMaskDirectiveTest {

    @Test
    void requiresColumnRefAndStrategy() {
        assertThatThrownBy(() -> new ColumnMaskDirective(null, MaskingStrategy.FULL, null, null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ColumnMaskDirective("c", null, null, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void nullParamsDefaultsToEmptyMap() {
        var directive = new ColumnMaskDirective("c", MaskingStrategy.HASH, null, UUID.randomUUID());
        assertThat(directive.params()).isEmpty();
    }

    @Test
    void paramsAreDefensivelyCopied() {
        var mutable = new HashMap<String, String>();
        mutable.put("visible_suffix", "4");
        var directive = new ColumnMaskDirective("c", MaskingStrategy.PARTIAL, mutable, null);
        mutable.put("visible_suffix", "9");
        assertThat(directive.params()).containsEntry("visible_suffix", "4");
    }
}
