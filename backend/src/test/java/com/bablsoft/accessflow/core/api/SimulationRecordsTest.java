package com.bablsoft.accessflow.core.api;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Value semantics for the policy-simulator api types (issue AF-630). */
class SimulationRecordsTest {

    private static final Instant FROM = Instant.parse("2026-06-01T00:00:00Z");
    private static final Instant TO = Instant.parse("2026-09-01T00:00:00Z");

    @Test
    void windowAcceptsAWellFormedPeriodInsideTheMaximum() {
        assertThatCode(() -> new SimulationWindow(FROM, TO).validate(Duration.ofDays(120)))
                .doesNotThrowAnyException();
    }

    @Test
    void windowAcceptsAnyLengthWhenNoMaximumIsGiven() {
        assertThatCode(() -> new SimulationWindow(FROM, TO).validate(null))
                .doesNotThrowAnyException();
    }

    @Test
    void windowRejectsAMissingBound() {
        assertThatThrownBy(() -> new SimulationWindow(null, TO).validate(Duration.ofDays(120)))
                .isInstanceOf(InvalidSimulationPeriodException.class);
        assertThatThrownBy(() -> new SimulationWindow(FROM, null).validate(Duration.ofDays(120)))
                .isInstanceOf(InvalidSimulationPeriodException.class);
    }

    @Test
    void windowRejectsAnInvertedOrEmptyPeriod() {
        assertThatThrownBy(() -> new SimulationWindow(TO, FROM).validate(Duration.ofDays(120)))
                .isInstanceOf(InvalidSimulationPeriodException.class);
        assertThatThrownBy(() -> new SimulationWindow(FROM, FROM).validate(Duration.ofDays(120)))
                .isInstanceOf(InvalidSimulationPeriodException.class);
    }

    @Test
    void windowRejectsAPeriodLongerThanTheMaximum() {
        assertThatThrownBy(() -> new SimulationWindow(FROM, TO).validate(Duration.ofDays(30)))
                .isInstanceOf(InvalidSimulationPeriodException.class);
    }

    @Test
    void windowAcceptsAPeriodExactlyAtTheMaximum() {
        var to = FROM.plus(Duration.ofDays(90));
        assertThatCode(() -> new SimulationWindow(FROM, to).validate(Duration.ofDays(90)))
                .doesNotThrowAnyException();
    }

    @Test
    void rowSecurityDraftDefensivelyCopiesItsScopeLists() {
        var roles = new ArrayList<String>();
        roles.add("ADMIN");
        var draft = new RowSecurityPolicyDraft(null, "orders", "tenant",
                RowSecurityOperator.EQUALS, RowSecurityValueType.LITERAL, "acme",
                roles, null, null, true);
        roles.add("ANALYST");

        assertThat(draft.appliesToRoles()).containsExactly("ADMIN");
        assertThat(draft.appliesToGroupIds()).isEmpty();
        assertThat(draft.appliesToUserIds()).isEmpty();
        assertThat(draft.replacesPolicyId()).isNull();
        assertThat(draft.enabled()).isTrue();
    }

    @Test
    void maskingDraftDefensivelyCopiesItsParamsAndRevealLists() {
        var params = new HashMap<String, String>();
        params.put("visible_suffix", "4");
        var draft = new MaskingPolicyDraft(UUID.randomUUID(), "customers.email",
                MaskingStrategy.PARTIAL, params, null, null, List.of(), false);
        params.put("visible_suffix", "8");

        assertThat(draft.strategyParams()).containsExactly(java.util.Map.entry("visible_suffix", "4"));
        assertThat(draft.revealToRoles()).isEmpty();
        assertThat(draft.revealToGroupIds()).isEmpty();
        assertThat(draft.enabled()).isFalse();
    }

    @Test
    void caveatEnumNamesEveryApproximationTheSimulatorCanMake() {
        assertThat(SimulationCaveat.values()).containsExactly(
                SimulationCaveat.MEMBERSHIP_STATE_CURRENT,
                SimulationCaveat.ANOMALY_STATE_CURRENT,
                SimulationCaveat.COLUMN_MATCH_BARE_NAME,
                SimulationCaveat.ENGINE_CLASSIFICATION_UNAVAILABLE,
                // AF-859: a hypothetical request, unlike a replayed one, carries neither a persisted
                // cost estimate nor any client context.
                SimulationCaveat.COST_ESTIMATE_ABSENT,
                SimulationCaveat.CLIENT_CONTEXT_ABSENT);
    }

    @Test
    void invalidPeriodExceptionCarriesNoLocalizedText() {
        // The detail is resolved from a message key by the handler; the exception must not carry
        // user-facing prose of its own.
        assertThat(new InvalidSimulationPeriodException())
                .hasMessage("Invalid policy-simulation period");
    }
}
