package com.bablsoft.accessflow.ai.internal.help;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The encoding exists so a background pass can record a failure that a request thread will later
 * render in the reader's language. Both halves have to survive the round trip, and the decode has to
 * be tolerant: {@code index_error} is a persisted column, so it will outlive this encoding.
 */
class HelpIndexErrorTest {

    @Test
    void roundTripsAKeyWithNoArguments() {
        var encoded = HelpIndexError.of("error.help_agent.rag_not_enabled").encode();

        assertThat(encoded).isEqualTo("error.help_agent.rag_not_enabled");
        var decoded = HelpIndexError.decode(encoded);
        assertThat(decoded).isNotNull();
        assertThat(decoded.messageKey()).isEqualTo("error.help_agent.rag_not_enabled");
        assertThat(decoded.args()).isEmpty();
    }

    @Test
    void roundTripsAKeyWithArguments() {
        var encoded = HelpIndexError
                .of("error.help_agent.index.dimension_mismatch", "768", "1536").encode();

        var decoded = HelpIndexError.decode(encoded);
        assertThat(decoded).isNotNull();
        assertThat(decoded.messageKey()).isEqualTo("error.help_agent.index.dimension_mismatch");
        assertThat(decoded.args()).containsExactly("768", "1536");
    }

    @Test
    void survivesAProviderMessageContainingPunctuationAndNewlines() {
        var providerText = "429 Too Many Requests: {\"error\": \"rate_limit\"}\nretry after 20s";

        var decoded = HelpIndexError
                .decode(HelpIndexError.of("error.help_agent.index.failed", providerText).encode());

        assertThat(decoded).isNotNull();
        assertThat(decoded.args()).containsExactly(providerText);
    }

    @Test
    void truncatesARuinouslyLongArgument() {
        var encoded = HelpIndexError.of("error.help_agent.index.failed", "x".repeat(5000)).encode();

        // index_error is TEXT, but a settings page is not the place for a 5000-character stack trace.
        assertThat(HelpIndexError.decode(encoded).args().getFirst()).hasSize(1000);
    }

    @Test
    void treatsANullArgumentAsEmptyRatherThanFailing() {
        var decoded = HelpIndexError
                .decode(new HelpIndexError("error.help_agent.index.failed",
                        java.util.Collections.singletonList(null)).encode());

        assertThat(decoded).isNotNull();
        assertThat(decoded.args()).containsExactly("");
    }

    @Test
    void refusesToDecodeAPlainSentence() {
        // What a row written before this encoding holds. The caller shows it verbatim rather than
        // dropping it: a stale reason still beats a blank field.
        assertThat(HelpIndexError.decode("Indexing failed: store down")).isNull();
    }

    @Test
    void refusesToDecodeAKeyFromSomewhereElse() {
        assertThat(HelpIndexError.decode("error.ai_config.rag.not_enabled")).isNull();
    }

    @Test
    void refusesToDecodeNothing() {
        assertThat(HelpIndexError.decode(null)).isNull();
        assertThat(HelpIndexError.decode("")).isNull();
        assertThat(HelpIndexError.decode("   ")).isNull();
    }
}
