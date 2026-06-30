package com.bablsoft.accessflow.requestgroups.api;

/** Thrown when a group or one of its members fails build-time validation (empty group, bad SQL, …). */
public class RequestGroupValidationException extends RequestGroupException {

    public RequestGroupValidationException(String message) {
        super(message);
    }
}
