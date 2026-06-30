package com.bablsoft.accessflow.requestgroups.api;

/** A submitter can never approve their own grouped request, regardless of role. */
public class SelfApprovalNotAllowedException extends RequestGroupException {

    public SelfApprovalNotAllowedException() {
        super("You cannot approve your own request group");
    }
}
