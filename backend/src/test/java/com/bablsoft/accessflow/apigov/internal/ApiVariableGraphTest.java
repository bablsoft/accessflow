package com.bablsoft.accessflow.apigov.internal;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ApiVariableGraphTest {

    private static ApiVariableGraph.Node node(String name, String expression) {
        return new ApiVariableGraph.Node(name, expression);
    }

    private static List<String> orderOf(List<ApiVariableGraph.Node> nodes) {
        return ApiVariableGraph.evaluationOrder(nodes).stream().map(ApiVariableGraph.Node::name).toList();
    }

    @Test
    void returnsEmptyForNoNodes() {
        assertThat(ApiVariableGraph.evaluationOrder(List.of())).isEmpty();
    }

    @Test
    void placesDependenciesBeforeDependents() {
        var order = orderOf(List.of(
                node("signature", "{{nonce}}{{timestamp}}"),
                node("nonce", null),
                node("timestamp", null)));

        assertThat(order).containsExactly("nonce", "timestamp", "signature");
    }

    @Test
    void resolvesTransitiveChains() {
        var order = orderOf(List.of(
                node("c", "{{b}}"),
                node("b", "{{a}}"),
                node("a", null)));

        assertThat(order).containsExactly("a", "b", "c");
    }

    @Test
    void followsQualifiedReferencesToo() {
        var order = orderOf(List.of(node("sig", "{{var.key}}"), node("key", null)));

        assertThat(order).containsExactly("key", "sig");
    }

    /**
     * Independent variables must evaluate in the caller's order, not hash order. This is observable:
     * two TIMESTAMP variables resolved in a different sequence can produce different values, and a
     * signature computed over them would then differ between nodes.
     */
    @Test
    void preservesInputOrderAmongIndependentNodes() {
        assertThat(orderOf(List.of(node("z", null), node("a", null), node("m", null))))
                .containsExactly("z", "a", "m");
    }

    @Test
    void preservesInputOrderAmongIndependentDependents() {
        var order = orderOf(List.of(
                node("base", null),
                node("second", "{{base}}"),
                node("first", "{{base}}")));

        assertThat(order).containsExactly("base", "second", "first");
    }

    @Test
    void ignoresBareReferencesToNamesThatDoNotExist() {
        // An unknown bare reference stays literal at render time, so it is not a dependency edge.
        assertThat(orderOf(List.of(node("a", "{{handlebarsToken}}")))).containsExactly("a");
    }

    @Test
    void rejectsQualifiedReferenceToAnUnknownName() {
        assertThatThrownBy(() -> ApiVariableGraph.evaluationOrder(List.of(node("a", "{{var.missing}}"))))
                .isInstanceOf(ApiVariableGraph.UnknownReferenceException.class)
                .satisfies(ex -> {
                    var e = (ApiVariableGraph.UnknownReferenceException) ex;
                    assertThat(e.from()).isEqualTo("a");
                    assertThat(e.missing()).isEqualTo("missing");
                });
    }

    @Test
    void rejectsSelfReference() {
        assertThatThrownBy(() -> ApiVariableGraph.evaluationOrder(List.of(node("a", "{{a}}"))))
                .isInstanceOf(ApiVariableGraph.CycleException.class)
                .satisfies(ex -> assertThat(((ApiVariableGraph.CycleException) ex).names())
                        .containsExactly("a"));
    }

    @Test
    void rejectsTwoNodeCycle() {
        assertThatThrownBy(() -> ApiVariableGraph.evaluationOrder(List.of(
                node("a", "{{b}}"), node("b", "{{a}}"))))
                .isInstanceOf(ApiVariableGraph.CycleException.class)
                .satisfies(ex -> assertThat(((ApiVariableGraph.CycleException) ex).names())
                        .containsExactlyInAnyOrder("a", "b"));
    }

    @Test
    void rejectsThreeNodeCycleAndNamesOnlyTheParticipants() {
        assertThatThrownBy(() -> ApiVariableGraph.evaluationOrder(List.of(
                node("standalone", null),
                node("a", "{{b}}"), node("b", "{{c}}"), node("c", "{{a}}"))))
                .isInstanceOf(ApiVariableGraph.CycleException.class)
                .satisfies(ex -> assertThat(((ApiVariableGraph.CycleException) ex).names())
                        .containsExactlyInAnyOrder("a", "b", "c"));
    }

    @Test
    void handlesADiamond() {
        var order = orderOf(List.of(
                node("top", "{{left}}{{right}}"),
                node("left", "{{base}}"),
                node("right", "{{base}}"),
                node("base", null)));

        assertThat(order).containsExactly("base", "left", "right", "top");
    }
}
