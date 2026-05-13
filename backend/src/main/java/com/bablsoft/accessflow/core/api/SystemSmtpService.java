package com.bablsoft.accessflow.core.api;

import java.util.Optional;
import java.util.UUID;

/**
 * Organization-scoped system SMTP configuration. Used by user-invitation flows
 * and as a fallback notification channel when an org has no EMAIL notification
 * channel configured.
 */
public interface SystemSmtpService {

    Optional<SystemSmtpView> findForOrganization(UUID organizationId);

    SystemSmtpView saveOrUpdate(UUID organizationId, SaveSystemSmtpCommand command);

    void delete(UUID organizationId);

    /**
     * Resolve the SMTP config including decrypted password for outbound send.
     * Returns empty if no row exists. Callers MUST treat the plaintext password
     * as transient and never log or persist it.
     */
    Optional<SystemSmtpSendingConfig> resolveSendingConfig(UUID organizationId);

    /**
     * Send a pre-rendered HTML email through the organization's system SMTP.
     *
     * @throws SystemSmtpNotConfiguredException if no system SMTP row exists for the org
     * @throws SystemSmtpDeliveryException     if SMTP submission fails
     */
    void sendSystemEmail(UUID organizationId, String toEmail, String subject, String htmlBody);

    /**
     * Send a synthetic test message. If {@code override} is non-null the test is sent against the
     * provided config (used by the admin UI's "Test" button before saving). Otherwise the persisted
     * row is used. {@code toEmail} defaults to the from-address when null/blank.
     */
    void sendTest(UUID organizationId, SaveSystemSmtpCommand override, String toEmail);
}
