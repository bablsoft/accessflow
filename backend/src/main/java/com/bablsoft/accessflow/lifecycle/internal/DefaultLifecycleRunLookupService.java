package com.bablsoft.accessflow.lifecycle.internal;

import com.bablsoft.accessflow.lifecycle.api.LifecycleRunLookupService;
import com.bablsoft.accessflow.lifecycle.api.LifecycleRunView;
import com.bablsoft.accessflow.lifecycle.internal.persistence.entity.LifecycleRunEntity;
import com.bablsoft.accessflow.lifecycle.internal.persistence.repo.LifecycleRunRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
class DefaultLifecycleRunLookupService implements LifecycleRunLookupService {

    private final LifecycleRunRepository repository;

    @Override
    @Transactional(readOnly = true)
    public List<LifecycleRunView> findForPeriod(UUID organizationId, Instant from, Instant to,
                                                UUID datasourceId, int limit) {
        return repository
                .findForPeriod(organizationId, from, to, datasourceId, PageRequest.of(0, limit))
                .stream()
                .map(DefaultLifecycleRunLookupService::toView)
                .toList();
    }

    private static LifecycleRunView toView(LifecycleRunEntity e) {
        return new LifecycleRunView(e.getId(), e.getOrganizationId(), e.getDatasourceId(),
                e.getKind(), e.getPolicyId(), e.getDeletionRequestId(), e.getStatus(), e.getAction(),
                e.getAffectedRows(), e.getMethod(), e.getStartedAt(), e.getFinishedAt(),
                e.getCreatedAt());
    }
}
