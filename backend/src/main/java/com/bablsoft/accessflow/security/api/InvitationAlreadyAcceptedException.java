package com.bablsoft.accessflow.security.api;

public class InvitationAlreadyAcceptedException extends RuntimeException {

    public InvitationAlreadyAcceptedException() {
        super("Invitation has already been accepted");
    }
}
