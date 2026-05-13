package com.bablsoft.accessflow.notifications.internal;

import com.bablsoft.accessflow.core.api.SystemSmtpSendingConfig;
import com.bablsoft.accessflow.core.api.SystemSmtpService;
import com.bablsoft.accessflow.notifications.internal.codec.EmailChannelConfig;
import com.bablsoft.accessflow.notifications.internal.strategy.EmailNotificationStrategy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Routes email notification events through the organization's system SMTP when no per-channel
 * EMAIL configuration is configured. Failures are isolated from the workflow state machine.
 */
@Component
@RequiredArgsConstructor
@Slf4j
class SystemEmailFallback {

    private final SystemSmtpService systemSmtpService;
    private final EmailNotificationStrategy emailStrategy;

    void deliverIfPossible(NotificationContext ctx) {
        if (ctx == null || ctx.organizationId() == null) {
            return;
        }
        var sendingConfig = systemSmtpService.resolveSendingConfig(ctx.organizationId()).orElse(null);
        if (sendingConfig == null) {
            log.debug("System SMTP fallback skipped for {} on query {} — not configured for org {}",
                    ctx.eventType(), ctx.queryRequestId(), ctx.organizationId());
            return;
        }
        try {
            emailStrategy.deliverInternal(ctx, toEmailChannelConfig(sendingConfig));
        } catch (RuntimeException ex) {
            log.error("System SMTP fallback delivery failed for {} on query {}",
                    ctx.eventType(), ctx.queryRequestId(), ex);
        }
    }

    private static EmailChannelConfig toEmailChannelConfig(SystemSmtpSendingConfig c) {
        return new EmailChannelConfig(
                c.host(),
                c.port(),
                c.username(),
                c.plaintextPassword(),
                c.tls(),
                c.fromAddress(),
                c.fromName());
    }
}
