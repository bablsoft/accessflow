package com.bablsoft.accessflow.security.api;

public class DuplicatePendingInvitationException extends RuntimeException {

    public DuplicatePendingInvitationException() {
        super("A pending invitation for this email already exists");
    }
}
