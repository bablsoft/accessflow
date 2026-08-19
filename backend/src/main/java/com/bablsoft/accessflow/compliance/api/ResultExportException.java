package com.bablsoft.accessflow.compliance.api;

public sealed class ResultExportException extends RuntimeException
        permits ResultExportNotFoundException, ResultExportUnavailableException,
        ResultExportDeniedException {

    protected ResultExportException(String message) {
        super(message);
    }
}
