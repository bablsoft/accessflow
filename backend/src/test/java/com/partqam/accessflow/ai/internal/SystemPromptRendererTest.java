package com.partqam.accessflow.ai.internal;

import com.partqam.accessflow.core.api.DatabaseSchemaView;
import com.partqam.accessflow.core.api.DbType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SystemPromptRendererTest {

    private final SystemPromptRenderer renderer = new SystemPromptRenderer();

    @Test
    void rendersTemplateWithSubstitutions() {
        var prompt = renderer.render("SELECT 1", DbType.POSTGRESQL, "public.users(id int pk)");

        assertThat(prompt).contains("Database type: POSTGRESQL");
        assertThat(prompt).contains("Schema context: public.users(id int pk)");
        assertThat(prompt).contains("SQL to analyze:");
        assertThat(prompt).contains("SELECT 1");
        assertThat(prompt).contains("\"risk_score\":");
        assertThat(prompt).contains("\"risk_level\":");
    }

    @Test
    void substitutesFallbackWhenSchemaIsNull() {
        var prompt = renderer.render("SELECT 1", DbType.MYSQL, null);

        assertThat(prompt).contains("Schema context: (no schema introspection available)");
    }

    @Test
    void substitutesFallbackWhenSchemaIsBlank() {
        var prompt = renderer.render("SELECT 1", DbType.MYSQL, "   ");

        assertThat(prompt).contains("Schema context: (no schema introspection available)");
    }

    @Test
    void describeSchemaProducesCompactRepresentation() {
        var schema = new DatabaseSchemaView(List.of(
                new DatabaseSchemaView.Schema("public", List.of(
                        new DatabaseSchemaView.Table("users", List.of(
                                new DatabaseSchemaView.Column("id", "uuid", false, true),
                                new DatabaseSchemaView.Column("email", "varchar", true, false)))))));

        var text = renderer.describeSchema(schema);

        assertThat(text).isEqualTo("public.users(id uuid pk not null, email varchar)");
    }

    @Test
    void describeSchemaReturnsNullForEmptyOrNullView() {
        assertThat(renderer.describeSchema(null)).isNull();
        assertThat(renderer.describeSchema(new DatabaseSchemaView(List.of()))).isNull();
    }

    @Test
    void describeSchemaJoinsMultipleTablesWithNewlines() {
        var schema = new DatabaseSchemaView(List.of(
                new DatabaseSchemaView.Schema("public", List.of(
                        new DatabaseSchemaView.Table("a", List.of(
                                new DatabaseSchemaView.Column("x", "int", true, false))),
                        new DatabaseSchemaView.Table("b", List.of(
                                new DatabaseSchemaView.Column("y", "int", true, false)))))));

        var text = renderer.describeSchema(schema);

        assertThat(text).isEqualTo("public.a(x int)\npublic.b(y int)");
    }
}
