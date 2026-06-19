package com.bablsoft.accessflow.core.api;

/**
 * Raised when a data-classification tag create request is structurally invalid — e.g. a blank
 * table name, an empty classification list, or a duplicate (object, classification) tag. The
 * {@code message} is a resolved, localized string supplied by the caller.
 */
public final class IllegalDataClassificationTagException extends DataClassificationTagException {

    public IllegalDataClassificationTagException(String message) {
        super(message);
    }
}
