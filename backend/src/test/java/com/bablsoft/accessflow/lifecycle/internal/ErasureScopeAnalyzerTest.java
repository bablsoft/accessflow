package com.bablsoft.accessflow.lifecycle.internal;

import com.bablsoft.accessflow.lifecycle.api.ErasureStatus;
import com.bablsoft.accessflow.lifecycle.api.LifecycleAction;
import com.bablsoft.accessflow.lifecycle.api.LifecycleSubjectType;
import com.bablsoft.accessflow.lifecycle.events.ErasureRequestSubmittedEvent;
import com.bablsoft.accessflow.lifecycle.events.ErasureScopeAnalyzedEvent;
import com.bablsoft.accessflow.lifecycle.internal.persistence.entity.DeletionRequestEntity;
import com.bablsoft.accessflow.lifecycle.internal.persistence.entity.RetentionPolicyEntity;
import com.bablsoft.accessflow.lifecycle.internal.persistence.repo.DeletionRequestRepository;
import com.bablsoft.accessflow.lifecycle.internal.persistence.repo.RetentionPolicyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ErasureScopeAnalyzerTest {

    private static final UUID REQ = UUID.randomUUID();
    private static final UUID ORG = UUID.randomUUID();
    private static final UUID DS = UUID.randomUUID();

    @Mock
    private DeletionRequestRepository requestRepository;
    @Mock
    private RetentionPolicyRepository policyRepository;
    @Mock
    private ApplicationEventPublisher eventPublisher;

    private ErasureScopeAnalyzer analyzer;

    @BeforeEach
    void setUp() {
        analyzer = new ErasureScopeAnalyzer(requestRepository, policyRepository, eventPublisher,
                new ObjectMapper());
    }

    private DeletionRequestEntity request(ErasureStatus status) {
        var e = new DeletionRequestEntity();
        e.setId(REQ);
        e.setOrganizationId(ORG);
        e.setDatasourceId(DS);
        e.setSubjectType(LifecycleSubjectType.EMAIL);
        e.setSubjectIdentifier("user@example.com");
        e.setStatus(status);
        return e;
    }

    @Test
    void onSubmitted_advancesToReviewWithScope() {
        var entity = request(ErasureStatus.PENDING_SCOPE_AI);
        when(requestRepository.findByIdForUpdate(REQ)).thenReturn(Optional.of(entity));
        var policy = new RetentionPolicyEntity();
        policy.setTargetTable("users");
        policy.setAction(LifecycleAction.SOFT_DELETE);
        when(policyRepository.findAllByDatasourceIdAndEnabledTrue(DS)).thenReturn(List.of(policy));

        analyzer.onSubmitted(new ErasureRequestSubmittedEvent(REQ, ORG, DS));

        assertThat(entity.getStatus()).isEqualTo(ErasureStatus.PENDING_REVIEW);
        assertThat(entity.getScopeSnapshot()).contains("users").contains("user@example.com");
        verify(eventPublisher).publishEvent(any(ErasureScopeAnalyzedEvent.class));
    }

    @Test
    void onSubmitted_skipsWhenMissing() {
        when(requestRepository.findByIdForUpdate(REQ)).thenReturn(Optional.empty());
        analyzer.onSubmitted(new ErasureRequestSubmittedEvent(REQ, ORG, DS));
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void onSubmitted_skipsWhenNotPendingScope() {
        when(requestRepository.findByIdForUpdate(REQ))
                .thenReturn(Optional.of(request(ErasureStatus.PENDING_REVIEW)));
        analyzer.onSubmitted(new ErasureRequestSubmittedEvent(REQ, ORG, DS));
        verify(eventPublisher, never()).publishEvent(any());
    }
}
