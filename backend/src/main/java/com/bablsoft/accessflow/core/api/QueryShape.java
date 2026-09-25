package com.bablsoft.accessflow.core.api;

/**
 * A structural feature of a parsed SQL statement (#940), detected from the JSqlParser AST anywhere in
 * the statement — the outer query, subqueries, CTE bodies and every statement of a transactional
 * batch. Feeds the {@code query_shape} routing condition and a grant's {@code denied_shapes}.
 *
 * <p>{@link #UNION} covers every set operation ({@code UNION}, {@code INTERSECT}, {@code EXCEPT},
 * {@code MINUS}). {@link #AGGREGATE} is the standard aggregate set by name (plus any ordered-set or
 * {@code FILTER}ed aggregate); a user-defined aggregate is not detected.
 */
public enum QueryShape {
    JOIN,
    UNION,
    SUBQUERY,
    CTE,
    GROUP_BY,
    HAVING,
    AGGREGATE,
    WINDOW_FUNCTION
}
