package com.bablsoft.accessflow.discovery.internal;

import com.bablsoft.accessflow.core.api.ResultColumn;
import com.bablsoft.accessflow.core.api.SelectExecutionResult;
import com.bablsoft.accessflow.discovery.internal.config.DiscoveryProperties;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class NestedValueFlattenerTest {

    private static NestedValueFlattener flattener(Integer depth, Integer leaves) {
        return new NestedValueFlattener(
                new DiscoveryProperties(null, null, null, null, null, depth, leaves, null, null));
    }

    private static NestedValueFlattener defaults() {
        return flattener(null, null);
    }

    private static SelectExecutionResult result(List<String> columnNames, List<List<Object>> rows) {
        var columns = columnNames.stream()
                .map(name -> new ResultColumn(name, 12, "object"))
                .toList();
        return new SelectExecutionResult(columns, rows, rows.size(), false, Duration.ofMillis(1),
                null, null, null);
    }

    private static Map<String, Object> map(Object... keyValues) {
        var out = new LinkedHashMap<String, Object>();
        for (var i = 0; i + 1 < keyValues.length; i += 2) {
            out.put((String) keyValues[i], keyValues[i + 1]);
        }
        return out;
    }

    @Test
    void collectsTopLevelStringsPreservingColumnOrder() {
        var out = defaults().collect(result(List.of("email", "name"),
                List.of(List.of("a@example.com", "Alice"), List.of("b@example.com", "Bob"))), 100);

        assertThat(out.keySet()).containsExactly("email", "name");
        assertThat(out.get("email")).containsExactly("a@example.com", "b@example.com");
    }

    @Test
    void seedsAnEmptyListForEveryTopLevelColumn() {
        var out = defaults().collect(result(List.of("email", "count"),
                List.of(List.of("a@example.com", 3))), 100);

        assertThat(out).containsKey("count");
        assertThat(out.get("count")).isEmpty();
    }

    @Test
    void flattensNestedMapIntoDotPath() {
        var out = defaults().collect(result(List.of("profile"),
                List.of(List.of(map("contact", map("email", "a@example.com"))))), 100);

        assertThat(out.get("profile.contact.email")).containsExactly("a@example.com");
    }

    @Test
    void listElementsShareTheParentPathWithoutAnIndexSegment() {
        var out = defaults().collect(result(List.of("contacts"),
                List.of(List.of(List.of(map("email", "a@example.com"),
                        map("email", "b@example.com"))))), 100);

        assertThat(out.keySet()).contains("contacts.email");
        assertThat(out.keySet()).noneMatch(key -> key.contains(".0."));
        assertThat(out.get("contacts.email")).containsExactly("a@example.com", "b@example.com");
    }

    @Test
    void ignoresNonStringScalarLeaves() {
        var out = defaults().collect(result(List.of("profile"),
                List.of(List.of(map("age", 42, "active", true, "email", "a@example.com")))), 100);

        assertThat(out).doesNotContainKeys("profile.age", "profile.active");
        assertThat(out.get("profile.email")).containsExactly("a@example.com");
    }

    @Test
    void ignoresBlankStringsAndNullCells() {
        var rows = List.<List<Object>>of(Collections.singletonList(null),
                List.of(map("email", "   ", "name", "Alice")));
        var out = defaults().collect(result(List.of("profile"), rows), 100);

        assertThat(out).doesNotContainKey("profile.email");
        assertThat(out.get("profile.name")).containsExactly("Alice");
    }

    @Test
    void stopsDescendingAtTheConfiguredDepth() {
        var deep = map("a", map("b", map("c", "a@example.com")));
        var out = flattener(2, null).collect(result(List.of("root"), List.of(List.of(deep))), 100);

        assertThat(out).doesNotContainKey("root.a.b.c");
    }

    @Test
    void perRowVisitBudgetTruncatesTheRowAndResetsForTheNext() {
        // Budget of 2 covers the map node plus one entry, so the second entry is never visited.
        var cell = map("first", "a@example.com", "second", "b@example.com");
        var rows = List.<List<Object>>of(List.of(cell), List.of(cell));
        var out = flattener(null, 2).collect(result(List.of("profile"), rows), 100);

        assertThat(out.get("profile.first")).hasSize(2);
        assertThat(out).doesNotContainKey("profile.second");
    }

    @Test
    void visitBudgetIsChargedForNonStringLeavesToo() {
        var cell = map("age", 1, "email", "a@example.com");
        var out = flattener(null, 2).collect(result(List.of("profile"),
                List.of(List.of(cell))), 100);

        assertThat(out).doesNotContainKey("profile.email");
    }

    @Test
    void topLevelScalarColumnsAreNotChargedAgainstTheVisitBudget() {
        var columns = new ArrayList<String>();
        var cells = new ArrayList<>();
        for (var i = 0; i < 50; i++) {
            columns.add("c" + i);
            cells.add("a@example.com");
        }
        var out = flattener(null, 1).collect(
                result(columns, List.of(List.copyOf(cells))), 100);

        assertThat(out.get("c49")).containsExactly("a@example.com");
    }

    @Test
    void topLevelNonStringScalarsDoNotConsumeTheBudgetBeforeADocumentColumn() {
        // A row of numeric columns must not exhaust the allowance before the walk reaches the
        // document column that comes later in the column order.
        var columns = new ArrayList<String>();
        var cells = new ArrayList<>();
        for (var i = 0; i < 50; i++) {
            columns.add("n" + i);
            cells.add(i);
        }
        columns.add("profile");
        cells.add(map("email", "a@example.com"));
        var out = flattener(null, 2).collect(
                result(columns, List.of(List.copyOf(cells))), 100);

        assertThat(out.get("profile.email")).containsExactly("a@example.com");
    }

    @Test
    void aWideRelationalTableNeverTripsTheNestedPathCeiling() {
        var columns = new ArrayList<String>();
        var cells = new ArrayList<>();
        for (var i = 0; i < NestedValueFlattener.MAX_NESTED_PATHS_PER_TABLE + 100; i++) {
            columns.add("c" + i);
            cells.add("a@example.com");
        }
        var out = defaults().collect(result(columns, List.of(List.copyOf(cells))), 100);

        assertThat(out).hasSize(columns.size());
        assertThat(out.get("c" + (columns.size() - 1))).containsExactly("a@example.com");
    }

    @Test
    void stopsOpeningNewPathsAtTheCeilingButKeepsFillingKnownOnes() {
        var wide = new LinkedHashMap<String, Object>();
        for (var i = 0; i < NestedValueFlattener.MAX_NESTED_PATHS_PER_TABLE + 50; i++) {
            wide.put("f" + i, "a@example.com");
        }
        var rows = List.<List<Object>>of(List.of(wide), List.of(wide));
        var out = flattener(null, 10_000).collect(result(List.of("doc"), rows), 100);

        // The seeded top-level column does not count against the nested ceiling.
        assertThat(out).hasSize(NestedValueFlattener.MAX_NESTED_PATHS_PER_TABLE + 1);
        assertThat(out.get("doc.f0")).hasSize(2);
    }

    @Test
    void capsValuesPerPathAtTheSampleSize() {
        var many = new ArrayList<>();
        for (var i = 0; i < 40; i++) {
            many.add(map("email", "a@example.com"));
        }
        var out = flattener(null, 10_000).collect(
                result(List.of("contacts"), List.of(List.of(List.copyOf(many)))), 10);

        assertThat(out.get("contacts.email")).hasSize(10);
    }

    @Test
    void skipsNullAndBlankMapKeys() {
        var cell = new LinkedHashMap<String, Object>();
        cell.put("  ", "a@example.com");
        cell.put("email", "b@example.com");
        var out = defaults().collect(result(List.of("profile"), List.of(List.of(cell))), 100);

        assertThat(out.keySet()).containsExactly("profile", "profile.email");
    }

    @Test
    void skipsPathsLongerThanTheNameLimit() {
        var longKey = "k".repeat(NestedValueFlattener.MAX_PATH_LENGTH + 1);
        var out = defaults().collect(result(List.of("profile"),
                List.of(List.of(map(longKey, "a@example.com")))), 100);

        assertThat(out.keySet()).containsExactly("profile");
    }

    @Test
    void nestedPathCollidingWithATopLevelColumnAppendsInsteadOfReplacing() {
        // Elasticsearch field names may contain dots, so both spellings can reach the same key.
        var rows = List.<List<Object>>of(
                List.of("flat@example.com", map("email", "nested@example.com")));
        var out = defaults().collect(result(List.of("profile.email", "profile"), rows), 100);

        assertThat(out.get("profile.email"))
                .containsExactly("flat@example.com", "nested@example.com");
    }

    @Test
    void deeplyNestedListsDoNotOverflowTheStack() {
        Object nested = "a@example.com";
        for (var i = 0; i < 10_000; i++) {
            nested = List.of(nested);
        }
        var out = flattener(null, 10_000).collect(
                result(List.of("root"), List.of(List.of(nested))), 100);

        assertThat(out.keySet()).containsExactly("root");
    }

    @Test
    void preservesFirstEncounterOrderOfNestedPaths() {
        var out = defaults().collect(result(List.of("a", "b"),
                List.of(List.of(map("z", "z@example.com"), map("y", "y@example.com")))), 100);

        assertThat(out.keySet()).containsExactly("a", "b", "a.z", "b.y");
    }
}
