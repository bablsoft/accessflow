package com.bablsoft.accessflow.sqlreview.internal.rules;

import org.junit.jupiter.api.Test;

import static com.bablsoft.accessflow.sqlreview.internal.rules.RuleTestSupport.parse;
import static org.assertj.core.api.Assertions.assertThat;

class LeadingWildcardsTest {

    @Test
    void findsOnlyLeadingWildcardLiterals() {
        var matches = LeadingWildcards.find(StatementWalker.walk(parse(
                "SELECT * FROM t WHERE a LIKE '%x' AND b LIKE 'y%' AND c NOT ILIKE '%z%' AND d LIKE e")));

        assertThat(matches).extracting(LeadingWildcards.Match::pattern).containsExactly("%x", "%z%");
        assertThat(matches.get(0).like()).isNotNull();
    }

    @Test
    void findsNothingWithoutLike() {
        assertThat(LeadingWildcards.find(StatementWalker.walk(parse("SELECT 1")))).isEmpty();
    }
}
