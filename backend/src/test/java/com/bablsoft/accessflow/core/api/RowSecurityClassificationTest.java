package com.bablsoft.accessflow.core.api;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RowSecurityClassificationTest {

    private static final String ENGINE = "mongodb";

    @Test
    void unknownFactoryHasNoReasonAndNoPolicies() {
        var result = RowSecurityClassification.unknown("cassandra");
        assertThat(result.outcome()).isEqualTo(RowSecurityOutcome.UNKNOWN);
        assertThat(result.engineId()).isEqualTo("cassandra");
        assertThat(result.reason()).isNull();
        assertThat(result.appliedPolicyIds()).isEmpty();
    }

    @Test
    void unknownFactoryCarriesEngineReason() {
        var result = RowSecurityClassification.unknown("cassandra", "needs live key columns");
        assertThat(result.outcome()).isEqualTo(RowSecurityOutcome.UNKNOWN);
        assertThat(result.reason()).isEqualTo("needs live key columns");
    }

    @Test
    void notApplicableFactoryCarriesNoPolicies() {
        var result = RowSecurityClassification.notApplicable(ENGINE);
        assertThat(result.outcome()).isEqualTo(RowSecurityOutcome.NOT_APPLICABLE);
        assertThat(result.appliedPolicyIds()).isEmpty();
        assertThat(result.reason()).isNull();
    }

    @Test
    void appliedFactoryKeepsThePolicyIds() {
        var policyId = UUID.randomUUID();
        var result = RowSecurityClassification.applied(ENGINE, Set.of(policyId));
        assertThat(result.outcome()).isEqualTo(RowSecurityOutcome.APPLIED);
        assertThat(result.appliedPolicyIds()).containsExactly(policyId);
        assertThat(result.reason()).isNull();
    }

    @Test
    void denyAllFactoryKeepsThePolicyIds() {
        var policyId = UUID.randomUUID();
        var result = RowSecurityClassification.denyAll(ENGINE, Set.of(policyId));
        assertThat(result.outcome()).isEqualTo(RowSecurityOutcome.DENY_ALL);
        assertThat(result.appliedPolicyIds()).containsExactly(policyId);
    }

    @Test
    void failClosedFactoryCarriesTheLocalizedReason() {
        var result = RowSecurityClassification.failClosed(ENGINE, "UNION over a protected table");
        assertThat(result.outcome()).isEqualTo(RowSecurityOutcome.FAIL_CLOSED);
        assertThat(result.reason()).isEqualTo("UNION over a protected table");
        assertThat(result.appliedPolicyIds()).isEmpty();
    }

    @Test
    void nullPolicyIdsBecomeAnEmptySet() {
        var result = new RowSecurityClassification(RowSecurityOutcome.APPLIED, ENGINE, null, null);
        assertThat(result.appliedPolicyIds()).isEmpty();
    }

    @Test
    void policyIdsAreDefensivelyCopied() {
        var mutable = new HashSet<UUID>();
        mutable.add(UUID.randomUUID());
        var result = new RowSecurityClassification(RowSecurityOutcome.APPLIED, ENGINE, mutable, null);
        mutable.add(UUID.randomUUID());
        assertThat(result.appliedPolicyIds()).hasSize(1);
    }

    @Test
    void outcomeIsRequired() {
        assertThatThrownBy(() -> new RowSecurityClassification(null, ENGINE, Set.of(), null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("outcome");
    }

    @Test
    void outcomeEnumCoversEveryStaticVerdict() {
        assertThat(RowSecurityOutcome.values()).containsExactly(
                RowSecurityOutcome.APPLIED, RowSecurityOutcome.DENY_ALL,
                RowSecurityOutcome.FAIL_CLOSED, RowSecurityOutcome.NOT_APPLICABLE,
                RowSecurityOutcome.UNKNOWN);
        assertThat(RowSecurityOutcome.valueOf("FAIL_CLOSED"))
                .isEqualTo(RowSecurityOutcome.FAIL_CLOSED);
    }

    @Test
    void requestRejectsANullQuery() {
        assertThatThrownBy(() -> new QueryEngineRowSecurityRequest(UUID.randomUUID(), null, List.of()))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("query");
    }

    @Test
    void requestNullDirectivesBecomeAnEmptyList() {
        var request = new QueryEngineRowSecurityRequest(UUID.randomUUID(), "db.orders.find({})", null);
        assertThat(request.directives()).isEmpty();
        assertThat(request.query()).isEqualTo("db.orders.find({})");
    }

    @Test
    void requestDirectivesAreDefensivelyCopied() {
        var directive = new RowSecurityDirective(UUID.randomUUID(), "orders", "tenant_id",
                RowSecurityOperator.EQUALS, List.of("acme"));
        var mutable = new java.util.ArrayList<RowSecurityDirective>();
        mutable.add(directive);
        var request = new QueryEngineRowSecurityRequest(UUID.randomUUID(), "select 1", mutable);
        mutable.clear();
        assertThat(request.directives()).containsExactly(directive);
    }
}
