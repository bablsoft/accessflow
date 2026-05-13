package com.bablsoft.accessflow.core.api;

public class SystemSmtpNotConfiguredException extends RuntimeException {

    public SystemSmtpNotConfiguredException() {
        super("System SMTP is not configured");
    }
}
