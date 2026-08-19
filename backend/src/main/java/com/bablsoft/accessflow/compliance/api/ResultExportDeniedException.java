package com.bablsoft.accessflow.compliance.api;

import com.bablsoft.accessflow.core.api.DataClassification;

import java.util.List;

/**
 * The effective export policy denies this export (#626). {@code classificationsPresent} carries
 * the classifications that triggered a classification-driven deny (empty for a blanket deny) so
 * the HTTP layer can localize a precise detail message.
 */
public final class ResultExportDeniedException extends ResultExportException {

    private final transient List<DataClassification> classificationsPresent;

    public ResultExportDeniedException(List<DataClassification> classificationsPresent) {
        super("Result export denied by policy");
        this.classificationsPresent = classificationsPresent == null ? List.of()
                : List.copyOf(classificationsPresent);
    }

    public List<DataClassification> classificationsPresent() {
        return classificationsPresent;
    }
}
