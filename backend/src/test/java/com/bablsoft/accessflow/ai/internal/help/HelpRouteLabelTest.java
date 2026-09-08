package com.bablsoft.accessflow.ai.internal.help;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The label the client says the user is on is the one chat field a page could fill straight from
 * {@code window.location}, and it lands in the system message sent to a third-party model. These are
 * the shapes that must never get that far.
 */
class HelpRouteLabelTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "/queries/2f1c8a9e-4d3b-4f21-9a77-1b0e5c6d7a88",
            "Query 2f1c8a9e-4d3b-4f21-9a77-1b0e5c6d7a88",
            "https://app.example.com/queries?id=7",
            "/reviews",
            "Reviews?filter=secret",
            "Reviews#pending",
            "C:\\Users\\tigran",
            "datasources/analytics-prod/tables/customer_pii",
            "Queries/detail",
            "Query 129384756",
            "Token a3f5c9d1e7b24680",
    })
    void dropsAnythingThatStillLooksLikeALocationOrCarriesAnIdentifier(String value) {
        assertThat(HelpRouteLabel.sanitize(value)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"Review queue", "Datasource settings", "Admin · Users", "Query editor"})
    void keepsARealLabel(String value) {
        assertThat(HelpRouteLabel.sanitize(value)).isEqualTo(value);
    }

    @Test
    void treatsMissingAndBlankAsNoLabel() {
        assertThat(HelpRouteLabel.sanitize(null)).isEmpty();
        assertThat(HelpRouteLabel.sanitize("   ")).isEmpty();
    }

    /**
     * Collapsed to one line before anything else: a newline would let a caller append its own line to
     * the rule list, which is the one place in the preamble where a line looks like a rule.
     */
    @Test
    void collapsesWhitespaceToASingleLine() {
        assertThat(HelpRouteLabel.sanitize("  Review\n  queue \t"))
                .isEqualTo("Review queue");
    }

    @Test
    void truncatesAnOverlongLabel() {
        var sanitized = HelpRouteLabel.sanitize("Queue ".repeat(HelpRouteLabel.MAX_LENGTH));

        assertThat(sanitized).hasSize(HelpRouteLabel.MAX_LENGTH);
    }

    /** A short number is part of a real label ("Stage 2 approvals"), not an id. */
    @Test
    void keepsAShortNumber() {
        assertThat(HelpRouteLabel.sanitize("Stage 2 approvals")).isEqualTo("Stage 2 approvals");
    }
}
