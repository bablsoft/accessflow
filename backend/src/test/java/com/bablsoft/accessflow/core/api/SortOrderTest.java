package com.bablsoft.accessflow.core.api;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SortOrderTest {

    @Test
    void ascFactoryProducesAscendingOrder() {
        var order = SortOrder.asc("createdAt");
        assertThat(order.property()).isEqualTo("createdAt");
        assertThat(order.direction()).isEqualTo(SortOrder.Direction.ASC);
    }

    @Test
    void descFactoryProducesDescendingOrder() {
        var order = SortOrder.desc("createdAt");
        assertThat(order.direction()).isEqualTo(SortOrder.Direction.DESC);
    }

    @Test
    void rejectsNullProperty() {
        assertThatThrownBy(() -> new SortOrder(null, SortOrder.Direction.ASC))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void rejectsNullDirection() {
        assertThatThrownBy(() -> new SortOrder("x", null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void rejectsBlankProperty() {
        assertThatThrownBy(() -> new SortOrder("   ", SortOrder.Direction.ASC))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
