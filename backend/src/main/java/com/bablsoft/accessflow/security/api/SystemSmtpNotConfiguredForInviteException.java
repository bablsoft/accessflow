package com.bablsoft.accessflow.security.api;

public class SystemSmtpNotConfiguredForInviteException extends RuntimeException {

    public SystemSmtpNotConfiguredForInviteException() {
        super("System SMTP must be configured before users can be invited");
    }
}
