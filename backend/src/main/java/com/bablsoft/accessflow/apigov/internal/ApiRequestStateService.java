package com.bablsoft.accessflow.apigov.internal;

import com.bablsoft.accessflow.apigov.api.ApiRequestNotFoundException;
import com.bablsoft.accessflow.apigov.events.ApiRequestStatusChangedEvent;
import com.bablsoft.accessflow.apigov.internal.persistence.entity.ApiRequestEntity;
import com.bablsoft.accessflow.apigov.internal.persistence.repo.ApiRequestRepository;
import com.bablsoft.accessflow.core.api.QueryStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Single chokepoint for API-request status transitions: applies the new status, persists, and
 * publishes {@link ApiRequestStatusChangedEvent}. Callers load the entity inside their own
 * transaction and pass it here so the version-checked save participates in that transaction.
 */
@Service
@RequiredArgsConstructor
public class ApiRequestStateService {

    private final ApiRequestRepository repository;
    private final ApplicationEventPublisher eventPublisher;

    public ApiRequestEntity require(UUID id) {
        return repository.findById(id).orElseThrow(() -> new ApiRequestNotFoundException(id));
    }

    /** Apply {@code next}, persist, and publish the status-changed event. No-op if already there. */
    public void apply(ApiRequestEntity entity, QueryStatus next) {
        var previous = entity.getStatus();
        if (previous == next) {
            return;
        }
        entity.setStatus(next);
        repository.save(entity);
        eventPublisher.publishEvent(new ApiRequestStatusChangedEvent(
                entity.getId(), entity.getSubmittedBy(), previous, next));
    }
}
