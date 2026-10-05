package com.bablsoft.accessflow.sqlreview.internal.rules;

import net.sf.jsqlparser.expression.StringValue;
import net.sf.jsqlparser.expression.operators.relational.LikeExpression;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code LIKE '%…'} detection (also {@code ILIKE}, negated or not), shared by
 * {@code leading_wildcard_like} and the custom-rule {@code like_leading_wildcard} fact (#1009).
 */
final class LeadingWildcards {

    private LeadingWildcards() {
    }

    record Match(LikeExpression like, String pattern) {
    }

    /** Every leading-wildcard {@code LIKE} the walker saw, in source order. */
    static List<Match> find(StatementWalker walker) {
        var out = new ArrayList<Match>();
        for (LikeExpression like : walker.likes()) {
            if (Tautologies.unwrap(like.getRightExpression()) instanceof StringValue pattern
                    && pattern.getValue() != null && pattern.getValue().startsWith("%")) {
                out.add(new Match(like, pattern.getValue()));
            }
        }
        return out;
    }
}
