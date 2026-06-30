package com.bablsoft.accessflow.requestgroups.api;

/** Thrown when a group transition is attempted from an incompatible current status. */
public class IllegalRequestGroupStateException extends RequestGroupException {

    private final transient RequestGroupStatus currentStatus;

    public IllegalRequestGroupStateException(RequestGroupStatus currentStatus, String message) {
        super(message);
        this.currentStatus = currentStatus;
    }

    public RequestGroupStatus currentStatus() {
        return currentStatus;
    }
}
