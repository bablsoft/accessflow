package com.bablsoft.accessflow.security.api;

public class InvitationNotFoundException extends RuntimeException {

    public InvitationNotFoundException() {
        super("Invitation not found");
    }
}
