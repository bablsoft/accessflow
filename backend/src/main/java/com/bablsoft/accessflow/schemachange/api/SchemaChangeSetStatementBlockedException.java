package com.bablsoft.accessflow.schemachange.api;

import java.util.List;

/**
 * A deterministic SQL review rule at {@code BLOCK} severity fired for at least one statement on at
 * least one target datasource, so the change set was not saved (#879). Carries only the blocking
 * findings. Mapped to HTTP 422.
 */
public final class SchemaChangeSetStatementBlockedException extends SchemaChangeException {

    private final List<SchemaChangeStatementFinding> findings;

    public SchemaChangeSetStatementBlockedException(List<SchemaChangeStatementFinding> findings) {
        super("Schema change set refused by " + (findings == null ? 0 : findings.size())
                + " blocking SQL review finding(s)");
        this.findings = findings == null ? List.of() : List.copyOf(findings);
    }

    public List<SchemaChangeStatementFinding> findings() {
        return findings;
    }
}
