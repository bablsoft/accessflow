package com.bablsoft.accessflow.core.internal;

import com.bablsoft.accessflow.core.api.QuerySubmitterEvidence;
import com.bablsoft.accessflow.core.api.QuerySubmitterEvidenceLookupService;
import com.bablsoft.accessflow.core.internal.persistence.repo.QuerySubmitterEvidenceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
class DefaultQuerySubmitterEvidenceLookupService implements QuerySubmitterEvidenceLookupService {

    private final QuerySubmitterEvidenceRepository repository;

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, QuerySubmitterEvidence> findBySubmitters(UUID organizationId,
                                                              Collection<UUID> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            // An empty IN list is not portable SQL; there is nothing to aggregate anyway.
            return Map.of();
        }
        var byUser = new LinkedHashMap<UUID, QuerySubmitterEvidence>();
        for (var row : repository.findBySubmitters(organizationId, userIds)) {
            byUser.put(row.getUserId(), new QuerySubmitterEvidence(
                    row.getUserId(),
                    row.getSubmittedQueryCount(),
                    row.getLastSubmittedAt(),
                    row.getBreakGlassExecutionCount(),
                    row.getLastBreakGlassAt()));
        }
        return byUser;
    }
}
