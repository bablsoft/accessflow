package com.bablsoft.accessflow.apigov.internal;

import com.bablsoft.accessflow.apigov.api.ApiProtocol;
import com.bablsoft.accessflow.apigov.internal.persistence.entity.ApiConnectorEntity;
import com.bablsoft.accessflow.apigov.internal.persistence.entity.ApiRequestEntity;
import com.bablsoft.accessflow.apigov.internal.persistence.repo.ApiConnectorRepository;
import com.bablsoft.accessflow.apigov.internal.persistence.repo.ApiRequestRepository;
import com.bablsoft.accessflow.core.api.AiAnalysisLookupService;
import com.bablsoft.accessflow.core.api.AiAnalysisSummaryView;
import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.core.api.RiskLevel;
import com.bablsoft.accessflow.core.api.SubmissionReason;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultApiRequestNotificationLookupServiceTest {

    @Mock private ApiRequestRepository requestRepository;
    @Mock private ApiConnectorRepository connectorRepository;
    @Mock private AiAnalysisLookupService aiAnalysisLookupService;

    private DefaultApiRequestNotificationLookupService service;

    @BeforeEach
    void setUp() {
        service = new DefaultApiRequestNotificationLookupService(requestRepository, connectorRepository,
                aiAnalysisLookupService);
    }

    @Test
    void emptyWhenRequestMissing() {
        var id = UUID.randomUUID();
        when(requestRepository.findById(id)).thenReturn(Optional.empty());
        assertThat(service.find(id)).isEmpty();
    }

    @Test
    void mapsConnectorNameAndRiskSummary() {
        var id = UUID.randomUUID();
        var connectorId = UUID.randomUUID();
        var analysisId = UUID.randomUUID();
        var e = new ApiRequestEntity();
        e.setId(id);
        e.setConnectorId(connectorId);
        e.setOrganizationId(UUID.randomUUID());
        e.setSubmittedBy(UUID.randomUUID());
        e.setVerb("POST");
        e.setRequestPath("/charges");
        e.setStatus(QueryStatus.PENDING_REVIEW);
        e.setSubmissionReason(SubmissionReason.USER_SUBMITTED);
        e.setAiAnalysisId(analysisId);
        when(requestRepository.findById(id)).thenReturn(Optional.of(e));
        var connector = new ApiConnectorEntity();
        connector.setName("Stripe");
        connector.setProtocol(ApiProtocol.REST);
        when(connectorRepository.findById(connectorId)).thenReturn(Optional.of(connector));
        when(aiAnalysisLookupService.findById(analysisId)).thenReturn(Optional.of(
                new AiAnalysisSummaryView(analysisId, null, RiskLevel.HIGH, 80, "risky", false, null)));

        var view = service.find(id).orElseThrow();

        assertThat(view.connectorName()).isEqualTo("Stripe");
        assertThat(view.aiRiskLevel()).isEqualTo(RiskLevel.HIGH);
        assertThat(view.aiRiskScore()).isEqualTo(80);
    }
}
