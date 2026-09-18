package com.bablsoft.accessflow.requestgroups.api;

/** Thrown when a group transition is attempted from an incompatible current status. */
public class IllegalRequestGroupStateException extends RequestGroupException {

    /**
     * A draft that already names one on-behalf-of principal cannot be submitted for another (#874):
     * the header on submit must match the draft, or be absent.
     */
    public static final class OnBehalfOfConflict extends IllegalRequestGroupStateException {
        public OnBehalfOfConflict(RequestGroupStatus currentStatus) {
            super(currentStatus, "Request group already names a different on-behalf-of principal");
        }
    }

    private final transient RequestGroupStatus currentStatus;

    public IllegalRequestGroupStateException(RequestGroupStatus currentStatus, String message) {
        super(message);
        this.currentStatus = currentStatus;
    }

    public RequestGroupStatus currentStatus() {
        return currentStatus;
    }
}
