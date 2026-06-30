package com.bablsoft.accessflow.requestgroups.internal.scheduled;

import com.bablsoft.accessflow.requestgroups.internal.GroupExecutionService;
import com.bablsoft.accessflow.requestgroups.internal.persistence.repo.RequestGroupRepository;
import lombok.RequiredArgsConstructor;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

/** Clustered-safe scan that runs APPROVED groups whose {@code scheduled_for} has been reached. */
@Component
@RequiredArgsConstructor
public class ScheduledGroupRunJob {

    private static final Logger log = LoggerFactory.getLogger(ScheduledGroupRunJob.class);

    private final RequestGroupRepository requestGroupRepository;
    private final GroupExecutionService groupExecutionService;

    @Scheduled(fixedDelayString = "${accessflow.requestgroups.run-poll-interval:PT1M}")
    @SchedulerLock(name = "scheduledGroupRunJob", lockAtMostFor = "PT10M", lockAtLeastFor = "PT30S")
    public void run() {
        var ids = requestGroupRepository.findScheduledDueIds(Instant.now());
        if (ids.isEmpty()) {
            log.debug("No scheduled request groups are due");
            return;
        }
        int fired = 0;
        for (UUID id : ids) {
            try {
                groupExecutionService.execute(id, null, "scheduled");
                fired++;
            } catch (RuntimeException ex) {
                log.error("Failed to fire scheduled request group {}", id, ex);
            }
        }
        log.info("Scheduled-run job fired {} request groups (scanned {})", fired, ids.size());
    }
}
