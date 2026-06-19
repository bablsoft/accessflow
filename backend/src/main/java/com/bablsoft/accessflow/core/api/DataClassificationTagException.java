package com.bablsoft.accessflow.core.api;

public sealed class DataClassificationTagException extends RuntimeException
        permits DataClassificationTagNotFoundException, IllegalDataClassificationTagException {

    protected DataClassificationTagException(String message) {
        super(message);
    }
}
