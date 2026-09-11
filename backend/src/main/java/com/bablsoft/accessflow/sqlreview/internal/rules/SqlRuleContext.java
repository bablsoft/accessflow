package com.bablsoft.accessflow.sqlreview.internal.rules;

import com.bablsoft.accessflow.sqlreview.api.SqlReviewFinding;
import net.sf.jsqlparser.parser.ASTNodeAccess;
import net.sf.jsqlparser.statement.Statement;

import java.util.Map;
import java.util.Objects;

/**
 * One parsed statement as seen by a rule (#862).
 *
 * @param statementIndex   zero-based position inside the submitted SQL
 * @param statement        the JSqlParser AST
 * @param transactional    whether the statement came from a {@code BEGIN…COMMIT} envelope
 * @param lineNumbersKnown {@code false} when the statement was re-parsed from a deparsed slice
 *                         (every envelope member), in which case AST lines are meaningless and
 *                         {@link #lineOf} always yields {@code null}
 */
public record SqlRuleContext(int statementIndex, Statement statement, boolean transactional,
                             boolean lineNumbersKnown) {

    public SqlRuleContext {
        Objects.requireNonNull(statement, "statement");
    }

    /** A verbatim single statement: real line numbers, no envelope. */
    public static SqlRuleContext of(Statement statement) {
        return new SqlRuleContext(0, statement, false, true);
    }

    /** One-based line of the construct's first token, or {@code null} when unknown. */
    public Integer lineOf(ASTNodeAccess node) {
        if (!lineNumbersKnown || node == null) {
            return null;
        }
        var ast = node.getASTNode();
        if (ast == null || ast.jjtGetFirstToken() == null) {
            return null;
        }
        return ast.jjtGetFirstToken().beginLine;
    }

    /** Builds a finding for {@code rule} on this statement, anchored at {@code node} when possible. */
    public SqlReviewFinding finding(SqlRule rule, ASTNodeAccess node, Map<String, String> args) {
        return new SqlReviewFinding(rule.ruleId(), rule.defaultSeverity(), statementIndex, lineOf(node), args);
    }
}
