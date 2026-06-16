package com.bablsoft.accessflow.ai.api;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OptimizationSuggestionTest {

    @Test
    void exposesComponents() {
        var suggestion = new OptimizationSuggestion(
                OptimizationType.INDEX,
                "Add index on orders(customer_id)",
                "Speeds up the customer filter.",
                "CREATE INDEX idx_orders_customer ON orders(customer_id)");

        assertThat(suggestion.type()).isEqualTo(OptimizationType.INDEX);
        assertThat(suggestion.title()).isEqualTo("Add index on orders(customer_id)");
        assertThat(suggestion.rationale()).isEqualTo("Speeds up the customer filter.");
        assertThat(suggestion.sql()).isEqualTo("CREATE INDEX idx_orders_customer ON orders(customer_id)");
    }

    @Test
    void valueEqualitySupportsDeduplication() {
        var a = new OptimizationSuggestion(OptimizationType.REWRITE, "t", "r", "SELECT 1");
        var b = new OptimizationSuggestion(OptimizationType.REWRITE, "t", "r", "SELECT 1");

        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
    }
}
