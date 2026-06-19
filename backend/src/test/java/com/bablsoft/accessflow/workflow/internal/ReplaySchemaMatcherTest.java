package com.bablsoft.accessflow.workflow.internal;

import com.bablsoft.accessflow.core.api.DatabaseSchemaView;
import com.bablsoft.accessflow.core.api.DatabaseSchemaView.Column;
import com.bablsoft.accessflow.core.api.DatabaseSchemaView.Schema;
import com.bablsoft.accessflow.core.api.DatabaseSchemaView.Table;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReplaySchemaMatcherTest {

    private DatabaseSchemaView target() {
        return new DatabaseSchemaView(List.of(
                new Schema("public", List.of(
                        table("users"), table("orders"))),
                new Schema("billing", List.of(
                        table("invoices")))));
    }

    private Table table(String name) {
        return new Table(name, List.of(new Column("id", "int4", false, true)), List.of());
    }

    @Test
    void emptyReferencesNeverMissing() {
        assertThat(ReplaySchemaMatcher.missingTables(List.of(), target())).isEmpty();
        assertThat(ReplaySchemaMatcher.missingTables(null, target())).isEmpty();
    }

    @Test
    void bareTableMatchesAnySchema() {
        assertThat(ReplaySchemaMatcher.missingTables(List.of("users", "invoices"), target()))
                .isEmpty();
    }

    @Test
    void qualifiedTableRequiresExactSchema() {
        assertThat(ReplaySchemaMatcher.missingTables(List.of("public.users"), target())).isEmpty();
        assertThat(ReplaySchemaMatcher.missingTables(List.of("public.invoices"), target()))
                .containsExactly("public.invoices");
    }

    @Test
    void reportsMissingTables() {
        assertThat(ReplaySchemaMatcher.missingTables(List.of("users", "payments"), target()))
                .containsExactly("payments");
    }

    @Test
    void normalizesCaseAndDeduplicates() {
        assertThat(ReplaySchemaMatcher.missingTables(List.of("USERS", "Users"), target())).isEmpty();
        assertThat(ReplaySchemaMatcher.missingTables(List.of("MISSING", "missing"), target()))
                .containsExactly("missing");
    }

    @Test
    void handlesNullTargetSchemaAsAllMissing() {
        assertThat(ReplaySchemaMatcher.missingTables(List.of("users"), new DatabaseSchemaView(null)))
                .containsExactly("users");
    }
}
