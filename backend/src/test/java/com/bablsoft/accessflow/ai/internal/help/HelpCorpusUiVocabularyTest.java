package com.bablsoft.accessflow.ai.internal.help;

import com.bablsoft.accessflow.ai.api.HelpChatRequest;
import com.bablsoft.accessflow.ai.internal.persistence.entity.HelpAgentConfigEntity;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The bundled corpus really does carry the interface's own vocabulary (#925).
 *
 * <p>Not the test of a class — there is none to test. The generator derives the menu labels and
 * control names from {@code Sidebar.tsx} and {@code en.json} and fails when the two disagree, but
 * nothing there asserts that the derived block survives into the artifact this JAR ships. That is
 * the half a reader feels: before this, the agent answered "open the SQL editor (/editor)" — a name
 * the sidebar does not use for that entry, and a URL instead of a path to click.
 *
 * <p>Reads the real {@code help-corpus/**} on the classpath, not a fixture, because a fixture would
 * pass while the shipped bundle rotted. Every literal asserted here is one the pre-#925 bundle did
 * <em>not</em> contain, so the test distinguishes the feature from its absence — a bare word like
 * "Navigation", or a label like "Submit for review" that the website prose already used, would have
 * passed before the generator changed and proved nothing.
 *
 * <p>That makes it a deliberate canary: renaming {@code nav.editor} fails a <em>Java</em> test whose
 * message points at this file, when the thing to change is the sidebar label and the regenerated
 * bundle. The literals below are the price of asserting against the real artifact.
 */
class HelpCorpusUiVocabularyTest {

    private static final String MENU_PATH = "Workflow → Database → Query editor";

    private static String resource(String name) throws Exception {
        var path = "help-corpus/" + name;
        try (InputStream in = HelpCorpusUiVocabularyTest.class.getClassLoader()
                .getResourceAsStream(path)) {
            assertThat(in).as("classpath resource %s", path).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /**
     * The mode with no citations at all, so the one most worth getting literally right: with no
     * index it is the only thing the model is given.
     */
    @Test
    void quickReferenceCarriesMenuPathsAndControlLabels() throws Exception {
        assertThat(resource("quick-reference.txt"))
                .contains(MENU_PATH)
                .contains("Query editor · /editor · needs QUERY_SUBMIT_DML")
                .contains("\"Justification\" — field")
                .contains("\"· required for review\" — note")
                .contains("\"Analyze\" — button")
                .contains("\"Submit for review\" — button")
                .contains("Say which permission a destination needs");
    }

    @Test
    void retrievableCorpusCarriesTheSameVocabulary() throws Exception {
        assertThat(resource("corpus.jsonl"))
                .contains(MENU_PATH)
                .contains("Query editor · /editor · needs QUERY_SUBMIT_DML")
                .contains("\\\"Submit for review\\\" — button")
                .contains("Finding your way around the AccessFlow interface > The sidebar menu");
    }

    /**
     * The preamble's worked example is a second, hand-written copy of a menu path (#925 review). It
     * can go stale in a way nothing else catches: rename the sidebar entry and the generator
     * regenerates the corpus, the assertions above fail on {@code MENU_PATH}, and a fixer who
     * updates only this file leaves the system prompt shipping a menu path that does not exist —
     * exactly the "plausible invention" the rule two lines above it forbids. Asserting both against
     * the same constant means the corpus and the prompt cannot drift apart silently.
     */
    @Test
    void thePromptsWorkedExampleIsAMenuPathTheCorpusActuallyCarries() throws Exception {
        var request = new HelpChatRequest(UUID.randomUUID(), UUID.randomUUID(), "q", List.of(),
                null, List.of(), null);
        var preamble = new HelpChatPromptRenderer()
                .render(new HelpAgentConfigEntity(), request, "q", List.of(), null, 100_000)
                .systemPreamble();

        assertThat(preamble).contains(MENU_PATH);
        assertThat(resource("quick-reference.txt")).contains(MENU_PATH);
    }
}
