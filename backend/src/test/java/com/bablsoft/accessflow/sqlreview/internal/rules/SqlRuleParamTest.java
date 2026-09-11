package com.bablsoft.accessflow.sqlreview.internal.rules;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SqlRuleParamTest {

    @Test
    void copiesDefaultsAndReadsNullAsEmpty() {
        var defaults = new ArrayList<>(List.of("a"));
        var param = new SqlRuleParam("names", true, defaults);
        defaults.add("b");
        assertThat(param.defaults()).containsExactly("a");
        assertThat(new SqlRuleParam("globs", false, null).defaults()).isEmpty();
    }
}
