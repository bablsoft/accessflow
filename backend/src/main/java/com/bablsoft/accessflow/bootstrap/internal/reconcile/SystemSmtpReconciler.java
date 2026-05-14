package com.bablsoft.accessflow.bootstrap.internal.reconcile;

import com.bablsoft.accessflow.bootstrap.internal.spec.SystemSmtpSpec;
import com.bablsoft.accessflow.core.api.SaveSystemSmtpCommand;
import com.bablsoft.accessflow.core.api.SystemSmtpService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
@RequiredArgsConstructor
@Slf4j
public class SystemSmtpReconciler {

    private final SystemSmtpService systemSmtpService;

    public void reconcile(UUID organizationId, SystemSmtpSpec spec) {
        if (spec == null || !spec.enabled()) {
            log.debug("Bootstrap: system SMTP disabled, skipping");
            return;
        }
        if (spec.host() == null || spec.host().isBlank()) {
            throw new IllegalStateException("accessflow.bootstrap.systemSmtp.host is required when enabled");
        }
        if (spec.port() == null) {
            throw new IllegalStateException("accessflow.bootstrap.systemSmtp.port is required when enabled");
        }
        if (spec.fromAddress() == null || spec.fromAddress().isBlank()) {
            throw new IllegalStateException(
                    "accessflow.bootstrap.systemSmtp.fromAddress is required when enabled");
        }
        systemSmtpService.saveOrUpdate(organizationId, new SaveSystemSmtpCommand(
                spec.host(),
                spec.port(),
                spec.username(),
                spec.password(),
                spec.tls() == null || spec.tls(),
                spec.fromAddress(),
                spec.fromName()));
        log.info("Bootstrap: applied system SMTP configuration for organization {}", organizationId);
    }
}
