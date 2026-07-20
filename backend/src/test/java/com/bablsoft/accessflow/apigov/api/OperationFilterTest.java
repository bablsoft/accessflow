package com.bablsoft.accessflow.apigov.api;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OperationFilterTest {

    @Test
    void emptyConstantIsEmpty() {
        assertThat(OperationFilter.EMPTY.isEmpty()).isTrue();
    }

    @Test
    void nullListsCanonicalizeToEmptyLists() {
        var f = new OperationFilter(null, null, null, null, null, null, null, null, false);

        assertThat(f.excludePaths()).isNotNull().isEmpty();
        assertThat(f.includeTags()).isNotNull().isEmpty();
        assertThat(f.isEmpty()).isTrue();
    }

    @Test
    void isEmptyFalseWhenAnyDimensionSet() {
        assertThat(new OperationFilter(null, List.of("/internal/**"), null, null, null, null, null, null, false)
                .isEmpty()).isFalse();
        assertThat(new OperationFilter(null, null, null, null, null, null, null, null, true)
                .isEmpty()).isFalse();
    }

    @Test
    void listsAreCopiedDefensively() {
        var src = new java.util.ArrayList<>(List.of("/a"));
        var f = new OperationFilter(null, src, null, null, null, null, null, null, false);
        src.add("/b");

        assertThat(f.excludePaths()).containsExactly("/a");
    }
}
