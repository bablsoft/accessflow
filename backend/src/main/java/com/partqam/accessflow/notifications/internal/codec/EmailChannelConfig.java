package com.partqam.accessflow.notifications.internal.codec;

public record EmailChannelConfig(
        String smtpHost,
        int smtpPort,
        String smtpUser,
        String smtpPasswordPlain,
        boolean smtpTls,
        String fromAddress,
        String fromName) {
}
