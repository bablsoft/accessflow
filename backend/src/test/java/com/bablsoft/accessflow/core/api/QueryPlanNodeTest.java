package com.bablsoft.accessflow.core.api;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class QueryPlanNodeTest {

    @Test
    void nullChildrenBecomesEmptyList() {
        var node = new QueryPlanNode("Seq Scan", "users", 100.0, 12.5, "Filter: age > 21", null);
        assertThat(node.children()).isEmpty();
    }

    @Test
    void leafConstructorDefaultsChildrenToEmpty() {
        var node = new QueryPlanNode("Index Scan", "orders", 5.0, 8.0, "Index Cond: id = 1");
        assertThat(node.children()).isEmpty();
        assertThat(node.operation()).isEqualTo("Index Scan");
        assertThat(node.target()).isEqualTo("orders");
        assertThat(node.estimatedRows()).isEqualTo(5.0);
        assertThat(node.estimatedCost()).isEqualTo(8.0);
        assertThat(node.detail()).isEqualTo("Index Cond: id = 1");
    }

    @Test
    void retainsChildrenAndIsImmutable() {
        var child = new QueryPlanNode("Seq Scan", "users", 100.0, 12.5, null);
        var root = new QueryPlanNode("Nested Loop", null, 100.0, 25.0, null, List.of(child));
        assertThat(root.children()).containsExactly(child);
    }

    @Test
    void nullableNumericFieldsAreAllowed() {
        var node = new QueryPlanNode("VALIDATE", null, null, null, "explanation", null);
        assertThat(node.estimatedRows()).isNull();
        assertThat(node.estimatedCost()).isNull();
    }
}
