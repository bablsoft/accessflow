package com.bablsoft.accessflow.ai.internal.help;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The bundled corpus really does carry the interface's own vocabulary (#925).
 *
 * <p>The generator derives the menu labels and control names from {@code Sidebar.tsx} and
 * {@code en.json} and fails when the two disagree, but nothing there asserts that the derived block
 * survives into the artifact this JAR ships. That is the half a reader feels: before this, the agent
 * answered "open the SQL editor (/editor)" — a screen name the menu does not use and a URL instead of
 * a path to click.
 *
 * <p>Reads the real {@code help-corpus/**} on the classpath, not a fixture, because a fixture would
 * pass while the shipped bundle rotted.
 */
class HelpCorpusUiVocabularyTest {

    private static final String MENU_PATH = "Workflow → Database → Query editor";

    private static String resource(String name) throws IOException {
        var resource = new DefaultResourceLoader().getResource("classpath:help-corpus/" + name);
        try (var in = resource.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /**
     * The mode with no citations at all, so the one most worth getting literally right: with no
     * index it is the only thing the model is given.
     */
    @Test
    void quickReferenceCarriesMenuPathsAndControlLabels() throws IOException {
        var quickReference = resource("quick-reference.txt");

        assertThat(quickReference)
                .contains(MENU_PATH)
                .contains("Query editor · /editor · needs QUERY_SUBMIT_DML")
                .contains("\"Justification\" — field")
                .contains("\"· required for review\" — note")
                .contains("\"Analyze\" — button")
                .contains("\"Submit for review\" — button")
                .contains("Say which permission a destination needs");
    }

    @Test
    void retrievableCorpusCarriesTheSameVocabulary() throws IOException {
        var corpus = resource("corpus.jsonl");

        assertThat(corpus)
                .contains("Navigation")
                .contains(MENU_PATH)
                .contains("Submit for review");
    }
}
