package com.bablsoft.accessflow.core.api;

public sealed class ExportPolicyException extends RuntimeException
        permits ExportPolicyNotFoundException, IllegalExportPolicyException {

    protected ExportPolicyException(String message) {
        super(message);
    }
}
