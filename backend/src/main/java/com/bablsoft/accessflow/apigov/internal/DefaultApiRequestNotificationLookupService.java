package com.bablsoft.accessflow.apigov.internal;

import com.bablsoft.accessflow.apigov.api.ApiRequestNotificationLookupService;
import com.bablsoft.accessflow.apigov.api.ApiRequestNotificationView;
import com.bablsoft.accessflow.apigov.internal.persistence.repo.ApiConnectorRepository;
import com.bablsoft.accessflow.apigov.internal.persistence.repo.ApiRequestRepository;
import com.bablsoft.accessflow.core.api.AiAnalysisLookupService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
class DefaultApiRequestNotificationLookupService implements ApiRequestNotificationLookupService {

    private final ApiRequestRepository requestRepository;
    private final ApiConnectorRepository connectorRepository;
    private final AiAnalysisLookupService aiAnalysisLookupService;

    @Override
    @Transactional(readOnly = true)
    public Optional<ApiRequestNotificationView> find(UUID apiRequestId) {
        return requestRepository.findById(apiRequestId).map(r -> {
            var connectorName = connectorRepository.findById(r.getConnectorId())
                    .map(c -> c.getName()).orElse(null);
            var summary = r.getAiAnalysisId() != null
                    ? aiAnalysisLookupService.findById(r.getAiAnalysisId()).orElse(null) : null;
            return new ApiRequestNotificationView(r.getId(), r.getOrganizationId(), r.getConnectorId(),
                    connectorName, r.getSubmittedBy(), r.getVerb(), r.getRequestPath(),
                    r.getSubmissionReason(),
                    summary != null ? summary.riskLevel() : null,
                    summary != null ? summary.riskScore() : null,
                    summary != null ? summary.summary() : null);
        });
    }
}
