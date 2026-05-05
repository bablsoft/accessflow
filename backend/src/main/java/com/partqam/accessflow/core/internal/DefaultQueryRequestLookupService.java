package com.partqam.accessflow.core.internal;

import com.partqam.accessflow.core.api.QueryRequestLookupService;
import com.partqam.accessflow.core.api.QueryRequestSnapshot;
import com.partqam.accessflow.core.internal.persistence.entity.QueryRequestEntity;
import com.partqam.accessflow.core.internal.persistence.repo.QueryRequestRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
class DefaultQueryRequestLookupService implements QueryRequestLookupService {

    private final QueryRequestRepository queryRequestRepository;

    @Override
    @Transactional(readOnly = true)
    public Optional<QueryRequestSnapshot> findById(UUID queryRequestId) {
        return queryRequestRepository.findById(queryRequestId)
                .map(DefaultQueryRequestLookupService::toSnapshot);
    }

    private static QueryRequestSnapshot toSnapshot(QueryRequestEntity entity) {
        return new QueryRequestSnapshot(
                entity.getId(),
                entity.getDatasource().getId(),
                entity.getDatasource().getOrganization().getId(),
                entity.getSubmittedBy().getId(),
                entity.getSqlText(),
                entity.getQueryType(),
                entity.getStatus());
    }
}
