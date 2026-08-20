package com.bablsoft.accessflow.audit.internal.sink;

/** A batch delivery to an external audit sink failed; the drain job records it and backs off. */
public class AuditSinkDeliveryException extends RuntimeException {

    public AuditSinkDeliveryException(String message) {
        super(message);
    }

    public AuditSinkDeliveryException(String message, Throwable cause) {
        super(message, cause);
    }
}
