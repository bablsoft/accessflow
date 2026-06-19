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
        assertThat(prompt).contains("\"optimizations\":");
    }

    @Test
    void optimizationInstructionIsEngineNativeNotSqlOnly() {
        // The optimization guidance must steer the model to the engine's native query language for
        // NoSQL engines (AF-451), not assume SQL CREATE INDEX. Substituting {{db_type}} should keep
        // the per-engine examples that make this work for MongoDB, Neo4j, DynamoDB, etc.
        var prompt = renderer.render("db.users.find({})", DbType.MONGODB, null, "en");

        assertThat(prompt).contains("Database type: MONGODB");
        assertThat(prompt).contains("SAME query language as the analyzed query for this MONGODB engine");
        assertThat(prompt).contains("db.collection.createIndex");
        assertThat(prompt).contains("CREATE INDEX FOR (n:Label)");
        assertThat(prompt).contains("Global Secondary Index");
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
                                new DatabaseSchemaView.Column("email", "varchar", true, false)),
                                List.of())))));

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
                                new DatabaseSchemaView.Column("x", "int", true, false)),
                                List.of()),
                        new DatabaseSchemaView.Table("b", List.of(
                                new DatabaseSchemaView.Column("y", "int", true, false)),
                                List.of())))));

        var text = renderer.describeSchema(schema);

        assertThat(text).isEqualTo("public.a(x int)\npublic.b(y int)");
    }

    @Test
    void describeSchemaAnnotatesFullyQualifiedRestrictedColumn() {
        var schema = new DatabaseSchemaView(List.of(
                new DatabaseSchemaView.Schema("public", List.of(
                        new DatabaseSchemaView.Table("users", List.of(
                                new DatabaseSchemaView.Column("id", "uuid", false, true),
                                new DatabaseSchemaView.Column("ssn", "text", true, false)),
                                List.of())))));

        var text = renderer.describeSchema(schema, List.of("public.users.ssn"));

        assertThat(text).contains("ssn text *RESTRICTED*");
        assertThat(text).doesNotContain("id uuid pk not null *RESTRICTED*");
    }

    @Test
    void describeSchemaMatchesRestrictedColumnByTableQualifiedName() {
        var schema = new DatabaseSchemaView(List.of(
                new DatabaseSchemaView.Schema("sales", List.of(
                        new DatabaseSchemaView.Table("orders", List.of(
                                new DatabaseSchemaView.Column("amount", "numeric", true, false)),
                                List.of())))));

        var text = renderer.describeSchema(schema, List.of("orders.amount"));

        assertThat(text).contains("amount numeric *RESTRICTED*");
    }

    @Test
    void describeSchemaMatchesRestrictedColumnByBareName() {
        var schema = new DatabaseSchemaView(List.of(
                new DatabaseSchemaView.Schema("public", List.of(
                        new DatabaseSchemaView.Table("users", List.of(
                                new DatabaseSchemaView.Column("password", "text", true, false)),
                                List.of())))));

        var text = renderer.describeSchema(schema, List.of("password"));

        assertThat(text).contains("password text *RESTRICTED*");
    }

    @Test
    void describeSchemaIgnoresBlankAndNullEntries() {
        var schema = new DatabaseSchemaView(List.of(
                new DatabaseSchemaView.Schema("public", List.of(
                        new DatabaseSchemaView.Table("users", List.of(
                                new DatabaseSchemaView.Column("email", "text", true, false)),
                                List.of())))));

        var text = renderer.describeSchema(schema, java.util.Arrays.asList("", "  ", null));

        assertThat(text).doesNotContain("*RESTRICTED*");
    }

    @Test
    void renderTemplateExplainsRestrictedColumnPolicy() {
        var prompt = renderer.render("SELECT 1", DbType.POSTGRESQL, "public.users(ssn text *RESTRICTED*)", "en");

        assertThat(prompt).contains("RESTRICTED_COLUMN_ACCESS");
        assertThat(prompt).contains("masked at the proxy layer");
    }

    @Test
    void rendersCustomTemplateSubstitutingAllNamedPlaceholders() {
        var template = "RULES. db={{db_type}} schema={{schema_context}} sql={{sql}} lang={{language}}";

        var prompt = renderer.render(template, "SELECT 1", DbType.POSTGRESQL, "public.users(id int)", null, "en");

        assertThat(prompt).isEqualTo("RULES. db=POSTGRESQL schema=public.users(id int) sql=SELECT 1 lang=English");
    }

    @Test
    void customTemplateFallsBackToSchemaPlaceholderWhenSchemaBlank() {
        var prompt = renderer.render("schema={{schema_context}} sql={{sql}}", "SELECT 1",
                DbType.MYSQL, "   ", null, "en");

        assertThat(prompt).contains("schema=(no schema introspection available)");
    }

    @Test
    void blankCustomTemplateFallsBackToDefault() {
        var fromBlank = renderer.render("   ", "SELECT 1", DbType.POSTGRESQL, null, null, "en");
        var fromNull = renderer.render(null, "SELECT 1", DbType.POSTGRESQL, null, null, "en");

        assertThat(fromBlank).isEqualTo(fromNull);
        assertThat(fromBlank).contains("database security and performance expert");
        assertThat(fromBlank).contains("Database type: POSTGRESQL");
    }

    @Test
    void sqlIsSubstitutedLastSoTokenStringInSqlIsNotReSubstituted() {
        // The SQL value itself contains a "{{language}}" token. Because {{sql}} is replaced last,
        // that literal must survive verbatim and must NOT be turned into the language name.
        var sql = "SELECT '{{language}}'";

        var prompt = renderer.render("sql={{sql}} lang={{language}}", sql, DbType.POSTGRESQL, null, null, "es");

        assertThat(prompt).isEqualTo("sql=SELECT '{{language}}' lang=Español");
    }

    @Test
    void defaultTemplateExposesBuiltInPromptWithSqlPlaceholder() {
        var template = renderer.defaultTemplate();

        assertThat(template).isEqualTo(SystemPromptRenderer.DEFAULT_TEMPLATE);
        assertThat(template).contains(SystemPromptRenderer.SQL_PLACEHOLDER);
        assertThat(template).contains("\"risk_score\":");
    }

    @Test
    void renderGenerationSubstitutesAllPlaceholders() {
        var prompt = renderer.renderGeneration("orders for last 5 days", DbType.POSTGRESQL,
                "public.orders(id int pk)", null, "en");

        assertThat(prompt).contains("Database type: POSTGRESQL");
        assertThat(prompt).contains("Schema context: public.orders(id int pk)");
        assertThat(prompt).contains("User request:");
        assertThat(prompt).contains("orders for last 5 days");
        assertThat(prompt).contains("\"sql\":");
        assertThat(prompt).contains("*RESTRICTED*");
        assertThat(prompt).doesNotContain("{{user_request}}");
    }

    @Test
    void renderGenerationFallsBackForBlankSchema() {
        var prompt = renderer.renderGeneration("x", DbType.MYSQL, "  ", null, "en");

        assertThat(prompt).contains("Schema context: (no schema introspection available)");
    }

    @Test
    void renderGenerationHandlesNullUserRequestAndUnknownLanguage() {
        var prompt = renderer.renderGeneration(null, DbType.POSTGRESQL, null, null, "xx");

        assertThat(prompt).contains("Respond in: English");
        assertThat(prompt).doesNotContain("{{user_request}}");
    }

    @Test
    void rendersRetrievedRagContextIntoToken() {
        var prompt = renderer.render(null, "SELECT 1", DbType.POSTGRESQL, "public.users(id int)",
                "Customer PII lives in users.ssn; never SELECT it.", "en");

        assertThat(prompt).contains("Knowledge base context");
        assertThat(prompt).contains("Customer PII lives in users.ssn; never SELECT it.");
        assertThat(prompt).doesNotContain("{{rag_context}}");
    }

    @Test
    void substitutesRagFallbackWhenContextNullOrBlank() {
        var fromNull = renderer.render(null, "SELECT 1", DbType.POSTGRESQL, null, null, "en");
        var fromBlank = renderer.render(null, "SELECT 1", DbType.POSTGRESQL, null, "  ", "en");

        assertThat(fromNull).contains("(no knowledge base context available)");
        assertThat(fromBlank).contains("(no knowledge base context available)");
    }

    @Test
    void renderGenerationRendersRagContext() {
        var prompt = renderer.renderGeneration("orders for last 5 days", DbType.POSTGRESQL,
                "public.orders(id int pk)", "Use created_at for date filters.", "en");

        assertThat(prompt).contains("Knowledge base context");
        assertThat(prompt).contains("Use created_at for date filters.");
        assertThat(prompt).doesNotContain("{{rag_context}}");
    }

    @Test
    void renderGenerationUsesSqlProfileForRelationalEngines() {
        var prompt = renderer.renderGeneration("x", DbType.POSTGRESQL, null, null, "en");

        assertThat(prompt).contains("Target query language: SQL");
        assertThat(prompt).contains("Use dialect-appropriate syntax");
        assertThat(prompt).doesNotContain("{{target_language}}");
        assertThat(prompt).doesNotContain("{{target_guidance}}");
    }

    @Test
    void renderGenerationIsMongoAware() {
        var prompt = renderer.renderGeneration("recent users", DbType.MONGODB, null, null, "en");

        assertThat(prompt).contains("the MongoDB query language");
        assertThat(prompt).contains("db.collection.find");
        assertThat(prompt).contains("db.collection.insertMany");
        assertThat(prompt).contains("creates it on first write");
        assertThat(prompt).contains("$where");
        assertThat(prompt).doesNotContain("Use dialect-appropriate syntax");
        // The JSON envelope key stays "sql" regardless of engine (wire compatibility).
        assertThat(prompt).contains("\"sql\":");
    }

    @Test
    void renderGenerationIsRedisAware() {
        var prompt = renderer.renderGeneration("x", DbType.REDIS, null, null, "en");

        assertThat(prompt).contains("redis-cli");
        assertThat(prompt).contains("EVAL");
    }

    @Test
    void renderGenerationIsCqlAwareForCassandraAndScylla() {
        for (var dbType : new DbType[]{DbType.CASSANDRA, DbType.SCYLLADB}) {
            var prompt = renderer.renderGeneration("x", dbType, null, null, "en");

            assertThat(prompt).contains("Target query language: CQL");
            assertThat(prompt).contains("ALLOW FILTERING");
        }
    }

    @Test
    void renderGenerationIsQueryDslAwareForElasticsearchAndOpenSearch() {
        for (var dbType : new DbType[]{DbType.ELASTICSEARCH, DbType.OPENSEARCH}) {
            var prompt = renderer.renderGeneration("x", dbType, null, null, "en");

            assertThat(prompt).contains("Query DSL");
            assertThat(prompt).contains("script_fields");
        }
    }

    @Test
    void renderGenerationIsCypherAwareForNeo4j() {
        var prompt = renderer.renderGeneration("x", DbType.NEO4J, null, null, "en");

        assertThat(prompt).contains("Target query language: Cypher");
        assertThat(prompt).contains("LOAD CSV");
    }

    @Test
    void syntaxForReturnsEngineSyntaxId() {
        assertThat(renderer.syntaxFor(DbType.POSTGRESQL, "SELECT 1")).isEqualTo("sql");
        assertThat(renderer.syntaxFor(DbType.COUCHBASE, "SELECT 1")).isEqualTo("sqlpp");
        assertThat(renderer.syntaxFor(DbType.DYNAMODB, "SELECT 1")).isEqualTo("partiql");
        assertThat(renderer.syntaxFor(DbType.REDIS, "GET k")).isEqualTo("cli");
        assertThat(renderer.syntaxFor(DbType.CASSANDRA, "SELECT 1")).isEqualTo("cql");
        assertThat(renderer.syntaxFor(DbType.SCYLLADB, "SELECT 1")).isEqualTo("cql");
        assertThat(renderer.syntaxFor(DbType.ELASTICSEARCH, "{}")).isEqualTo("query_dsl");
        assertThat(renderer.syntaxFor(DbType.OPENSEARCH, "{}")).isEqualTo("query_dsl");
        assertThat(renderer.syntaxFor(DbType.NEO4J, "MATCH (n) RETURN n")).isEqualTo("cypher");
    }

    @Test
    void syntaxForDetectsMongoShellVsJsonCommand() {
        assertThat(renderer.syntaxFor(DbType.MONGODB, "db.users.find({})")).isEqualTo("shell");
        assertThat(renderer.syntaxFor(DbType.MONGODB, "  { \"find\": \"users\" }")).isEqualTo("json");
        assertThat(renderer.syntaxFor(DbType.MONGODB, null)).isEqualTo("shell");
    }
}
