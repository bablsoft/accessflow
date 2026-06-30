package com.bablsoft.accessflow.lifecycle.api;

/** Thrown when the submitter of a deletion request attempts to approve or reject it themselves. */
public final class ErasureSelfApprovalException extends LifecycleException {

    public ErasureSelfApprovalException() {
        super("A reviewer cannot decide their own deletion request");
    }
}
