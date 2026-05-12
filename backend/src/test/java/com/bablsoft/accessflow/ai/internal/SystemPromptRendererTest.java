package com.bablsoft.accessflow.ai.internal;

import com.bablsoft.accessflow.core.api.DatabaseSchemaView;
import com.bablsoft.accessflow.core.api.DbType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SystemPromptRendererTest {

    private final SystemPromptRenderer renderer = new SystemPromptRenderer();

    @Test
    void rendersTemplateWithSubstitutions() {
        var prompt = renderer.render("SELECT 1", DbType.POSTGRESQL, "public.users(id int pk)", "en");

        assertThat(prompt).contains("Database type: POSTGRESQL");
        assertThat(prompt).contains("Schema context: public.users(id int pk)");
        assertThat(prompt).contains("SQL to analyze:");
        assertThat(prompt).contains("SELECT 1");
        assertThat(prompt).contains("\"risk_score\":");
        assertThat(prompt).contains("\"risk_level\":");
        assertThat(prompt).contains("Respond in: English");
    }

    @Test
    void substitutesFallbackWhenSchemaIsNull() {
        var prompt = renderer.render("SELECT 1", DbType.MYSQL, null, "en");

        assertThat(prompt).contains("Schema context: (no schema introspection available)");
    }

    @Test
    void substitutesFallbackWhenSchemaIsBlank() {
        var prompt = renderer.render("SELECT 1", DbType.MYSQL, "   ", "en");

        assertThat(prompt).contains("Schema context: (no schema introspection available)");
    }

    @Test
    void rendersWithSpanishDirective() {
        var prompt = renderer.render("SELECT 1", DbType.POSTGRESQL, null, "es");

        assertThat(prompt).contains("Respond in: Español");
    }

    @Test
    void rendersFallsBackToEnglishWhenLanguageUnknown() {
        var prompt = renderer.render("SELECT 1", DbType.POSTGRESQL, null, "xx");

        assertThat(prompt).contains("Respond in: English");
    }

    @Test
    void rendersFallsBackToEnglishWhenLanguageNull() {
        var prompt = renderer.render("SELECT 1", DbType.POSTGRESQL, null, null);

        assertThat(prompt).contains("Respond in: English");
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

    @Test
    void describeSchemaAnnotatesFullyQualifiedRestrictedColumn() {
        var schema = new DatabaseSchemaView(List.of(
                new DatabaseSchemaView.Schema("public", List.of(
                        new DatabaseSchemaView.Table("users", List.of(
                                new DatabaseSchemaView.Column("id", "uuid", false, true),
                                new DatabaseSchemaView.Column("ssn", "text", true, false)))))));

        var text = renderer.describeSchema(schema, List.of("public.users.ssn"));

        assertThat(text).contains("ssn text *RESTRICTED*");
        assertThat(text).doesNotContain("id uuid pk not null *RESTRICTED*");
    }

    @Test
    void describeSchemaMatchesRestrictedColumnByTableQualifiedName() {
        var schema = new DatabaseSchemaView(List.of(
                new DatabaseSchemaView.Schema("sales", List.of(
                        new DatabaseSchemaView.Table("orders", List.of(
                                new DatabaseSchemaView.Column("amount", "numeric", true, false)))))));

        var text = renderer.describeSchema(schema, List.of("orders.amount"));

        assertThat(text).contains("amount numeric *RESTRICTED*");
    }

    @Test
    void describeSchemaMatchesRestrictedColumnByBareName() {
        var schema = new DatabaseSchemaView(List.of(
                new DatabaseSchemaView.Schema("public", List.of(
                        new DatabaseSchemaView.Table("users", List.of(
                                new DatabaseSchemaView.Column("password", "text", true, false)))))));

        var text = renderer.describeSchema(schema, List.of("password"));

        assertThat(text).contains("password text *RESTRICTED*");
    }

    @Test
    void describeSchemaIgnoresBlankAndNullEntries() {
        var schema = new DatabaseSchemaView(List.of(
                new DatabaseSchemaView.Schema("public", List.of(
                        new DatabaseSchemaView.Table("users", List.of(
                                new DatabaseSchemaView.Column("email", "text", true, false)))))));

        var text = renderer.describeSchema(schema, java.util.Arrays.asList("", "  ", null));

        assertThat(text).doesNotContain("*RESTRICTED*");
    }

    @Test
    void renderTemplateExplainsRestrictedColumnPolicy() {
        var prompt = renderer.render("SELECT 1", DbType.POSTGRESQL, "public.users(ssn text *RESTRICTED*)", "en");

        assertThat(prompt).contains("RESTRICTED_COLUMN_ACCESS");
        assertThat(prompt).contains("masked at the proxy layer");
    }
}
