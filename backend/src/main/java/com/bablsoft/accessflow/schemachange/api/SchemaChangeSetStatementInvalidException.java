package com.bablsoft.accessflow.schemachange.api;

import com.bablsoft.accessflow.core.api.QueryType;

/**
 * One statement failed the authoring gate (#879). Mapped to HTTP 422 with one error code per
 * {@link Reason}. {@code queryType} is set for {@link Reason#DML} only; {@code parserMessage} — the
 * parser's own, already localized, reason — for {@link Reason#UNPARSEABLE} only.
 */
public final class SchemaChangeSetStatementInvalidException extends SchemaChangeException {

    public enum Reason {
        /** Blank, or the engine-aware parser rejected it. */
        UNPARSEABLE,
        /** Classified {@code SELECT} / {@code INSERT} / {@code UPDATE} / {@code DELETE}. */
        DML,
        /** Opens a transaction — each statement of a change set runs on its own. */
        TRANSACTION_ENVELOPE,
        /** Holds more than one statement. */
        MULTIPLE_STATEMENTS
    }

    private final int statementIndex;
    private final Reason reason;
    private final QueryType queryType;
    private final String parserMessage;

    public SchemaChangeSetStatementInvalidException(int statementIndex, Reason reason, QueryType queryType,
                                                    String parserMessage) {
        super("Schema change set statement " + statementIndex + " rejected: " + reason);
        this.statementIndex = statementIndex;
        this.reason = reason;
        this.queryType = queryType;
        this.parserMessage = parserMessage;
    }

    public static SchemaChangeSetStatementInvalidException unparseable(int statementIndex, String parserMessage) {
        return new SchemaChangeSetStatementInvalidException(statementIndex, Reason.UNPARSEABLE, null, parserMessage);
    }

    public static SchemaChangeSetStatementInvalidException dml(int statementIndex, QueryType queryType) {
        return new SchemaChangeSetStatementInvalidException(statementIndex, Reason.DML, queryType, null);
    }

    public static SchemaChangeSetStatementInvalidException transactionEnvelope(int statementIndex) {
        return new SchemaChangeSetStatementInvalidException(statementIndex, Reason.TRANSACTION_ENVELOPE, null, null);
    }

    public static SchemaChangeSetStatementInvalidException multipleStatements(int statementIndex) {
        return new SchemaChangeSetStatementInvalidException(statementIndex, Reason.MULTIPLE_STATEMENTS, null, null);
    }

    public int statementIndex() {
        return statementIndex;
    }

    public Reason reason() {
        return reason;
    }

    public QueryType queryType() {
        return queryType;
    }

    public String parserMessage() {
        return parserMessage;
    }
}
