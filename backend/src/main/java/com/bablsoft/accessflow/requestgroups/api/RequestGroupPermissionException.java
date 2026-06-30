package com.bablsoft.accessflow.requestgroups.api;

/**
 * Thrown when the submitter lacks permission for a member target — a user can only bundle a
 * datasource or API connector they are permitted to use; a break-glass group additionally requires
 * {@code can_break_glass} on every member target.
 */
public class RequestGroupPermissionException extends RequestGroupException {

    public RequestGroupPermissionException(String message) {
        super(message);
    }
}
