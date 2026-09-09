package com.bablsoft.accessflow.engine.redis;

import com.bablsoft.accessflow.core.api.RowSecurityDirective;
import com.bablsoft.accessflow.core.api.RowSecurityOperator;
import com.bablsoft.accessflow.core.api.RowSecurityOutcome;
import com.bablsoft.accessflow.core.api.UnrewritableRowSecurityException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RedisRowSecurityClassifierTest {

    private static final String ENGINE = "redis";

    private final RedisCommandParser parser = new RedisCommandParser(TestMessages.of(Map.of()));
    private final RedisRowSecurityClassifier classifier = new RedisRowSecurityClassifier(
            TestMessages.of(Map.of("error.row_security_redis_unsupported",
                    "Row security cannot be applied to the key prefix ''{0}''")));

    private static RowSecurityDirective directive(String tableRef) {
        return new RowSecurityDirective(UUID.randomUUID(), tableRef, "tenant",
                RowSecurityOperator.EQUALS, List.of("acme"));
    }

    @Test
    void classifyReportsNotApplicableWhenNoDirectiveTargetsAReferencedPrefix() {
        var parsed = parser.parseCommand("GET session:42");
        var result = classifier.classify(ENGINE, parsed, List.of(directive("orders")));
        assertThat(result.outcome()).isEqualTo(RowSecurityOutcome.NOT_APPLICABLE);
        assertThat(result.engineId()).isEqualTo(ENGINE);
        assertThat(result.reason()).isNull();
    }

    @Test
    void classifyReportsNotApplicableForNoDirectivesAtAll() {
        var parsed = parser.parseCommand("GET session:42");
        assertThat(classifier.classify(ENGINE, parsed, List.of()).outcome())
                .isEqualTo(RowSecurityOutcome.NOT_APPLICABLE);
        assertThat(classifier.classify(ENGINE, parsed, null).outcome())
                .isEqualTo(RowSecurityOutcome.NOT_APPLICABLE);
    }

    @Test
    void classifyAlwaysFailsClosedWhenADirectiveTargetsAReferencedPrefix() {
        var parsed = parser.parseCommand("GET session:42");
        var result = classifier.classify(ENGINE, parsed, List.of(directive("session")));
        // Redis can never filter a key-value read, so an applicable directive denies outright —
        // it is never APPLIED and never DENY_ALL.
        assertThat(result.outcome()).isEqualTo(RowSecurityOutcome.FAIL_CLOSED);
        assertThat(result.reason()).contains("session");
        assertThat(result.appliedPolicyIds()).isEmpty();
    }

    @Test
    void classifyMatchesTheLastDotSegmentOfATableRefCaseInsensitively() {
        var parsed = parser.parseCommand("GET session:42");
        assertThat(classifier.classify(ENGINE, parsed, List.of(directive("public.SESSION"))).outcome())
                .isEqualTo(RowSecurityOutcome.FAIL_CLOSED);
    }

    @Test
    void classifyIgnoresANullTableRef() {
        var parsed = parser.parseCommand("GET session:42");
        var nullRef = new RowSecurityDirective(UUID.randomUUID(), "orders", "tenant",
                RowSecurityOperator.EQUALS, List.of("acme"));
        assertThat(classifier.classify(ENGINE, parsed, List.of(nullRef)).outcome())
                .isEqualTo(RowSecurityOutcome.NOT_APPLICABLE);
    }

    @Test
    void failClosedOnRowSecurityThrowsForAMatchingPrefix() {
        var parsed = parser.parseCommand("GET session:42");
        assertThatThrownBy(() ->
                classifier.failClosedOnRowSecurity(parsed, List.of(directive("session"))))
                .isInstanceOf(UnrewritableRowSecurityException.class)
                .hasMessageContaining("session");
    }

    @Test
    void failClosedOnRowSecurityPassesWhenNothingMatches() {
        var parsed = parser.parseCommand("GET session:42");
        assertThatCode(() -> classifier.failClosedOnRowSecurity(parsed, List.of(directive("orders"))))
                .doesNotThrowAnyException();
        assertThatCode(() -> classifier.failClosedOnRowSecurity(parsed, List.of()))
                .doesNotThrowAnyException();
        assertThatCode(() -> classifier.failClosedOnRowSecurity(parsed, null))
                .doesNotThrowAnyException();
    }

    @Test
    void failClosedForPrefixThrowsForAMatchingPrefix() {
        assertThatThrownBy(() ->
                classifier.failClosedForPrefix("session", List.of(directive("session"))))
                .isInstanceOf(UnrewritableRowSecurityException.class)
                .hasMessageContaining("session");
    }

    @Test
    void failClosedForPrefixPassesWhenNothingMatches() {
        assertThatCode(() -> classifier.failClosedForPrefix("session", List.of(directive("orders"))))
                .doesNotThrowAnyException();
        assertThatCode(() -> classifier.failClosedForPrefix("session", null))
                .doesNotThrowAnyException();
    }

    @Test
    void enforcementAndClassificationAgreeOnTheSameInput() {
        var parsed = parser.parseCommand("GET session:42");
        var directives = List.of(directive("session"));
        assertThat(classifier.classify(ENGINE, parsed, directives).outcome())
                .isEqualTo(RowSecurityOutcome.FAIL_CLOSED);
        assertThatThrownBy(() -> classifier.failClosedOnRowSecurity(parsed, directives))
                .isInstanceOf(UnrewritableRowSecurityException.class);
    }
}
