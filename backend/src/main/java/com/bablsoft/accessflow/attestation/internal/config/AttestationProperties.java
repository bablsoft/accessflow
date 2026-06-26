package com.bablsoft.accessflow.attestation.internal.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Tunables for the attestation (access-recertification) module.
 *
 * <ul>
 *   <li>{@code openPollInterval} — cadence of {@code AttestationCampaignOpenJob} (also the
 *       {@code @Scheduled} fixedDelayString fallback so the job has a default at startup).</li>
 *   <li>{@code closePollInterval} — cadence of {@code AttestationCampaignCloseJob}.</li>
 *   <li>{@code maxEvidenceRows} — hard cap on item rows written into a single evidence CSV export;
 *       beyond it the export is flagged truncated.</li>
 * </ul>
 */
@ConfigurationProperties("accessflow.attestation")
public record AttestationProperties(
        Duration openPollInterval,
        Duration closePollInterval,
        int maxEvidenceRows) {

    public AttestationProperties {
        if (openPollInterval == null) {
            openPollInterval = Duration.ofMinutes(5);
        }
        if (closePollInterval == null) {
            closePollInterval = Duration.ofMinutes(5);
        }
        if (maxEvidenceRows <= 0) {
            maxEvidenceRows = 50_000;
        }
    }
}
