package com.bablsoft.accessflow.core.api;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RowSecurityRecordsTest {

    @Test
    void operatorMultiValueFlag() {
        assertThat(RowSecurityOperator.IN.isMultiValue()).isTrue();
        assertThat(RowSecurityOperator.NOT_IN.isMultiValue()).isTrue();
        assertThat(RowSecurityOperator.EQUALS.isMultiValue()).isFalse();
        assertThat(RowSecurityOperator.GREATER_THAN_OR_EQUAL.isMultiValue()).isFalse();
    }

    @Test
    void resolvedPredicateNullValuesBecomesEmpty() {
        var predicate = new ResolvedRowSecurityPredicate(UUID.randomUUID(), "orders", "region",
                RowSecurityOperator.EQUALS, null);
        assertThat(predicate.values()).isEmpty();
    }

    @Test
    void resolvedPredicateValuesAreDefensivelyCopied() {
        var mutable = new ArrayList<Object>(List.of("EU"));
        var predicate = new ResolvedRowSecurityPredicate(UUID.randomUUID(), "orders", "region",
                RowSecurityOperator.IN, mutable);
        mutable.clear();
        assertThat(predicate.values()).containsExactly("EU");
    }

    @Test
    void viewNullCollectionsBecomeEmpty() {
        var view = new RowSecurityPolicyView(UUID.randomUUID(), UUID.randomUUID(), "orders",
                "region", RowSecurityOperator.EQUALS, RowSecurityValueType.LITERAL, "EU",
                null, null, null, true, Instant.EPOCH, Instant.EPOCH);

        assertThat(view.appliesToRoles()).isEmpty();
        assertThat(view.appliesToGroupIds()).isEmpty();
        assertThat(view.appliesToUserIds()).isEmpty();
    }

    @Test
    void viewRetainsValues() {
        var groupId = UUID.randomUUID();
        var userId = UUID.randomUUID();
        var view = new RowSecurityPolicyView(UUID.randomUUID(), UUID.randomUUID(), "orders",
                "region", RowSecurityOperator.IN, RowSecurityValueType.VARIABLE, "user.groups",
                List.of("ANALYST"), List.of(groupId), List.of(userId), false, Instant.EPOCH,
                Instant.EPOCH);

        assertThat(view.tableName()).isEqualTo("orders");
        assertThat(view.operator()).isEqualTo(RowSecurityOperator.IN);
        assertThat(view.valueType()).isEqualTo(RowSecurityValueType.VARIABLE);
        assertThat(view.appliesToRoles()).containsExactly("ANALYST");
        assertThat(view.appliesToGroupIds()).containsExactly(groupId);
        assertThat(view.appliesToUserIds()).containsExactly(userId);
        assertThat(view.enabled()).isFalse();
    }
}
