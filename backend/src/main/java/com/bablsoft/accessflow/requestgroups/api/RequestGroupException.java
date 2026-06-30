package com.bablsoft.accessflow.requestgroups.api;

/** Base type for request-group domain exceptions. */
public abstract class RequestGroupException extends RuntimeException {

    protected RequestGroupException(String message) {
        super(message);
    }
}
