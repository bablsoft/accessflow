package com.bablsoft.accessflow.apigov.internal.scheduled;

import com.bablsoft.accessflow.apigov.events.ApiRequestDecidedEvent;
import com.bablsoft.accessflow.apigov.internal.ApiRequestStateService;
import com.bablsoft.accessflow.apigov.internal.persistence.repo.ApiRequestRepository;
import com.bablsoft.accessflow.core.api.QueryStatus;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;

/**
 * Auto-rejects (TIMED_OUT) API requests that have sat in PENDING_REVIEW past the configured review
 * timeout. Clustered-safe via ShedLock; per-row failures are logged and do not abort the batch.
 */
@Component
public class ApiRequestTimeoutJob {

    private static final Logger log = LoggerFactory.getLogger(ApiRequestTimeoutJob.class);

    private final ApiRequestRepository requestRepository;
    private final ApiRequestStateService stateService;
    private final ApplicationEventPublisher eventPublisher;
    private final Duration reviewTimeout;

    public ApiRequestTimeoutJob(ApiRequestRepository requestRepository,
                                ApiRequestStateService stateService,
                                ApplicationEventPublisher eventPublisher,
                                @Value("${accessflow.apigov.review-timeout:PT24H}") Duration reviewTimeout) {
        this.requestRepository = requestRepository;
        this.stateService = stateService;
        this.eventPublisher = eventPublisher;
        this.reviewTimeout = reviewTimeout;
    }

    @Scheduled(fixedDelayString = "${accessflow.apigov.timeout-poll-interval:PT5M}")
    @SchedulerLock(name = "apiRequestTimeoutJob", lockAtMostFor = "PT10M", lockAtLeastFor = "PT30S")
    public void run() {
        var cutoff = Instant.now().minus(reviewTimeout);
        for (var id : requestRepository.findStalePendingReviewIds(cutoff)) {
            try {
                expire(id);
            } catch (RuntimeException ex) {
                log.error("Failed to time out API request {}", id, ex);
            }
        }
    }

    @Transactional
    void expire(java.util.UUID id) {
        var request = stateService.require(id);
        if (request.getStatus() != QueryStatus.PENDING_REVIEW) {
            return;
        }
        stateService.apply(request, QueryStatus.TIMED_OUT);
        eventPublisher.publishEvent(new ApiRequestDecidedEvent(id, QueryStatus.TIMED_OUT, "review_timeout"));
    }
}
