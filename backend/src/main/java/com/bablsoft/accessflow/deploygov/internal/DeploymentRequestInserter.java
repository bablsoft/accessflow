package com.bablsoft.accessflow.deploygov.internal;

import com.bablsoft.accessflow.deploygov.internal.persistence.entity.DeploymentRequestEntity;
import com.bablsoft.accessflow.deploygov.internal.persistence.repo.DeploymentRequestRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Inserts a deployment request in <strong>its own</strong> transaction.
 *
 * <p>This exists for one reason: the trigger-idempotency race. When two CI runners fire the same
 * run concurrently, one of them loses {@code uq_deployment_requests_trigger_idem} and the submit
 * path wants to re-read the winning row. Postgres aborts the whole transaction block on a
 * constraint violation — every later statement in it fails with "current transaction is aborted" —
 * and Hibernate additionally marks the session rollback-only. Recovering inside the caller's own
 * transaction is therefore impossible; the insert needs a separate boundary so only <em>it</em>
 * rolls back and the caller can still query.
 *
 * <p>The exception is deliberately <em>not</em> caught here: a {@code @Transactional} method that
 * swallows a flush failure and returns normally fails again at its own commit with
 * {@code UnexpectedRollbackException}. The caller catches it, outside this boundary.
 */
@Component
@RequiredArgsConstructor
class DeploymentRequestInserter {

    private final DeploymentRequestRepository requestRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void insert(DeploymentRequestEntity entity) {
        requestRepository.saveAndFlush(entity);
    }
}
