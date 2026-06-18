package com.bablsoft.accessflow.workflow.internal.routing;

import com.bablsoft.accessflow.workflow.api.ConditionNode;
import com.bablsoft.accessflow.workflow.api.IllegalRoutingPolicyException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticMessageSource;

import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

class RoutingConditionValidatorTest {

    private RoutingConditionValidator validator;

    @BeforeEach
    void setUp() {
        var messages = new StaticMessageSource();
        messages.addMessage("error.routing_policy_cidr_invalid", Locale.getDefault(),
                "Not a valid CIDR block: {0}");
        validator = new RoutingConditionValidator(messages);
    }

    @Test
    void acceptsValidSourceIpCidrs() {
        assertThatCode(() -> validator.validate(
                new ConditionNode.SourceIpMatches(List.of("10.0.0.0/8", "2001:db8::/32"))))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsInvalidSourceIpCidr() {
        assertThatExceptionOfType(IllegalRoutingPolicyException.class).isThrownBy(() ->
                validator.validate(new ConditionNode.SourceIpMatches(List.of("10.0.0.0/8", "nope"))));
    }

    @Test
    void walksCombinatorsRecursively() {
        var nested = new ConditionNode.And(List.of(
                new ConditionNode.QueryTypeIn(Set.of()),
                new ConditionNode.Not(new ConditionNode.SourceIpMatches(List.of("bad/cidr")))));
        assertThatExceptionOfType(IllegalRoutingPolicyException.class)
                .isThrownBy(() -> validator.validate(nested));
        var orNode = new ConditionNode.Or(List.of(
                new ConditionNode.SourceIpMatches(List.of("192.0.2.0/24"))));
        assertThatCode(() -> validator.validate(orNode)).doesNotThrowAnyException();
    }

    @Test
    void ignoresNonIpLeavesAndNull() {
        assertThatCode(() -> validator.validate(new ConditionNode.CiCdOrigin(true)))
                .doesNotThrowAnyException();
        assertThatCode(() -> validator.validate(null)).doesNotThrowAnyException();
    }
}
