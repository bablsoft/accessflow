package com.bablsoft.accessflow.bootstrap.internal;

import java.util.List;

/**
 * Thrown by {@code BootstrapRunner} when one or more reconcilers fail. The pod fails its readiness
 * probe so the operator sees a clear, K8s-native failure signal instead of a half-applied state.
 */
public class BootstrapException extends RuntimeException {

    private final List<String> reconcileErrors;

    public BootstrapException(List<String> reconcileErrors) {
        super(buildMessage(reconcileErrors));
        this.reconcileErrors = List.copyOf(reconcileErrors);
    }

    public List<String> reconcileErrors() {
        return reconcileErrors;
    }

    private static String buildMessage(List<String> errors) {
        return "Bootstrap reconciliation failed with %d error(s): %s".formatted(errors.size(), String.join("; ", errors));
    }
}
