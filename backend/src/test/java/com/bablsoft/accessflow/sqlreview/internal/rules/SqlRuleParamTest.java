package com.bablsoft.accessflow.sqlreview.internal.rules;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SqlRuleParamTest {

    @Test
    void copiesDefaultsAndReadsNullAsEmpty() {
        var defaults = new ArrayList<>(List.of("a"));
        var pattern = Pattern.compile("[a-z]+");
        var param = new SqlRuleParam("names", true, defaults, pattern, "error.x");
        defaults.add("b");
        assertThat(param.defaults()).containsExactly("a");
        assertThat(param.valuePattern()).isSameAs(pattern);
        assertThat(param.invalidValueKey()).isEqualTo("error.x");
        assertThat(new SqlRuleParam("globs", false, null, pattern, "error.x").defaults()).isEmpty();
    }

    @Test
    void rejectsMissingKeyPatternOrMessageKey() {
        var pattern = Pattern.compile(".*");
        assertThatThrownBy(() -> new SqlRuleParam(null, true, List.of(), pattern, "error.x"))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new SqlRuleParam("k", true, List.of(), null, "error.x"))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new SqlRuleParam("k", true, List.of(), pattern, null))
                .isInstanceOf(NullPointerException.class);
    }
}
