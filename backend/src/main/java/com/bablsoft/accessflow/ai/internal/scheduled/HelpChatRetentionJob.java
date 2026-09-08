package com.bablsoft.accessflow.ai.internal.scheduled;

import com.bablsoft.accessflow.ai.internal.persistence.entity.HelpAgentConfigEntity;
import com.bablsoft.accessflow.ai.internal.persistence.repo.HelpAgentConfigRepository;
import com.bablsoft.accessflow.ai.internal.persistence.repo.HelpChatSessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;

/**
 * Deletes help conversations past their organization's {@code retention_days} (AF-904).
 *
 * <p>One statement per organization, and the messages go with the session through the
 * {@code ON DELETE CASCADE} on {@code help_chat_messages.session_id} — nothing is loaded, so a
 * conversation with a thousand messages costs the same as an empty one and the {@code @Version}
 * column on the session never comes into it.
 *
 * <p>The work list is <em>every</em> configured organization, enabled or not. Retention is a promise
 * about data already written, not about data still being produced — and disabling the agent is the
 * most likely reaction to a privacy concern, so it is the last moment at which stored transcripts
 * should become immortal. Nothing else prunes these rows. The sweep needs no model, no embedding and
 * no bound {@code ai_config}, so a disabled organization costs one statement.
 *
 * <p>A non-positive {@code retention_days} is skipped rather than honoured. The admin API validates
 * the range [1, 3650], so it can only arrive by direct database edit — and a cutoff of "now" would
 * delete every conversation the organization has, which is not a thing to infer from a bad row.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class HelpChatRetentionJob {

    private final HelpAgentConfigRepository configRepository;
    private final HelpChatSessionRepository sessionRepository;
    private final Clock clock;

    @Scheduled(fixedDelayString = "${accessflow.help-agent.retention-poll-interval:PT6H}")
    @SchedulerLock(name = "helpChatRetentionJob", lockAtMostFor = "PT30M", lockAtLeastFor = "PT5M")
    public void run() {
        var configs = configRepository.findAll();
        if (configs.isEmpty()) {
            log.debug("No organizations have configured the help agent; nothing to expire");
            return;
        }
        var deleted = 0;
        for (var config : configs) {
            try {
                deleted += expire(config);
            } catch (RuntimeException ex) {
                log.error("Help chat retention failed for organization {}",
                        config.getOrganizationId(), ex);
            }
        }
        log.info("Help chat retention deleted {} conversations across {} organizations",
                deleted, configs.size());
    }

    private int expire(HelpAgentConfigEntity config) {
        var retentionDays = config.getRetentionDays();
        if (retentionDays <= 0) {
            log.warn("Skipping help chat retention for organization {}: retention_days is {}",
                    config.getOrganizationId(), retentionDays);
            return 0;
        }
        var cutoff = clock.instant().minus(Duration.ofDays(retentionDays));
        var deleted = sessionRepository.deleteByOrganizationIdAndLastActivityBefore(
                config.getOrganizationId(), cutoff);
        if (deleted > 0) {
            log.info("Deleted {} help conversations older than {} days for organization {}",
                    deleted, retentionDays, config.getOrganizationId());
        }
        return deleted;
    }
}
