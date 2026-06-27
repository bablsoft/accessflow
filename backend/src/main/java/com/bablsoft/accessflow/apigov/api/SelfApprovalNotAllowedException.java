package com.bablsoft.accessflow.apigov.api;

/** A submitter can never approve their own API request, regardless of role. */
public class SelfApprovalNotAllowedException extends ApiGovException {

    public SelfApprovalNotAllowedException() {
        super("You cannot approve your own API request");
    }
}
