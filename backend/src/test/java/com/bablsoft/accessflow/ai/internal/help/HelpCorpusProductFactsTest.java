package com.bablsoft.accessflow.ai.internal.help;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The bundled corpus really does say what AccessFlow is not.
 *
 * <p>Same shape as {@link HelpCorpusUiVocabularyTest}, for the same reason: the generator and the
 * website tests guard their own halves, but nothing else asserts that the product-boundary facts
 * survive into the artifact this JAR ships. Before this, asked whether a C# application could reach
 * AccessFlow over ODBC or ADO.NET, the agent answered "I don't know" — every retrieved excerpt
 * described outbound JDBC drivers, and no page said the product exposes no wire protocol inbound.
 *
 * <p>Reads the real {@code help-corpus/**} on the classpath, not a fixture. Every literal asserted
 * here was absent from the pre-change bundle, so the test distinguishes the feature from its
 * absence. The "complete" markers matter as much as the negatives: {@code HelpChatPromptRenderer}
 * lets the model say "not offered" only from a list an excerpt calls complete.
 */
class HelpCorpusProductFactsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String INTEGRATIONS_PAGE = "website/docs/integrations/index.html";

    private static String resource(String name) throws Exception {
        var path = "help-corpus/" + name;
        try (InputStream in = HelpCorpusProductFactsTest.class.getClassLoader()
                .getResourceAsStream(path)) {
            assertThat(in).as("classpath resource %s", path).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** The no-index mode is the one with nothing else to go on, so the block must be there whole. */
    @Test
    void quickReferenceCarriesTheProductBoundariesAndMarksItsListsComplete() throws Exception {
        var quickReference = resource("quick-reference.txt");

        assertThat(quickReference)
                .contains("What AccessFlow is, and what it is not")
                .contains("Every way in (complete list)")
                .contains("no ODBC, JDBC or ADO.NET driver")
                .contains("no database wire protocol")
                .contains("Sign-in methods (complete list)")
                .contains("AI providers (complete list)")
                .contains("Supported engines (complete list)")
                .contains("is not a hosted service");
        // Above the navigation block: the renderer truncates the tail first, so the facts must sit
        // ahead of the ~4k tokens of menu and control labels rather than behind them.
        assertThat(quickReference.indexOf("What AccessFlow is, and what it is not"))
                .isLessThan(quickReference.indexOf("Where things are in the app"));
    }

    /** The engine line is derived from the connector catalog, not retyped, so it names each engine. */
    @Test
    void quickReferenceEngineLineNamesEveryCatalogConnector() throws Exception {
        var quickReference = resource("quick-reference.txt");

        assertThat(quickReference)
                .contains("PostgreSQL")
                .contains("Neo4j")
                .contains("Snowflake")
                .contains("ClickHouse")
                .contains("any other JDBC-compatible engine via an admin-uploaded driver JAR");
    }

    /** The retrieval mode answers from the chapter itself, so the chunks must carry the same facts. */
    @Test
    void corpusCarriesTheIntegrationsChapterWithTheOdbcAnswer() throws Exception {
        var texts = resource("corpus.jsonl").lines()
                .filter(line -> !line.isBlank())
                .map(line -> MAPPER.readTree(line))
                .filter(node -> INTEGRATIONS_PAGE.equals(node.path("path").asString()))
                .map(node -> node.path("text").asString())
                .toList();

        assertThat(texts).as("chunks from %s", INTEGRATIONS_PAGE).isNotEmpty();
        var joined = String.join("\n", texts);
        assertThat(joined)
                .contains("Integrations & boundaries")
                .contains("ODBC")
                .contains("ADO.NET")
                .contains("no database wire protocol")
                .contains("This list is complete.")
                .contains("The complete list of engines AccessFlow governs")
                .contains("The complete list of ways a person or a program can authenticate")
                .contains("The complete list of providers AccessFlow can call")
                .contains("POST /api/v1/queries");
    }
}
