package com.bablsoft.accessflow.security.api;

public class InvitationRevokedException extends RuntimeException {

    public InvitationRevokedException() {
        super("Invitation has been revoked");
    }
}
