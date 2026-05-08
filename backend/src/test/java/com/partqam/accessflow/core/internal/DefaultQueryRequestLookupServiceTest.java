package com.partqam.accessflow.core.internal;

import com.partqam.accessflow.core.api.AiProviderType;
import com.partqam.accessflow.core.api.QueryListFilter;
import com.partqam.accessflow.core.api.QueryStatus;
import com.partqam.accessflow.core.api.QueryType;
import com.partqam.accessflow.core.api.RiskLevel;
import com.partqam.accessflow.core.api.UserRoleType;
import com.partqam.accessflow.core.internal.persistence.entity.AiAnalysisEntity;
import com.partqam.accessflow.core.internal.persistence.entity.DatasourceEntity;
import com.partqam.accessflow.core.internal.persistence.entity.OrganizationEntity;
import com.partqam.accessflow.core.internal.persistence.entity.QueryRequestEntity;
import com.partqam.accessflow.core.internal.persistence.entity.UserEntity;
import com.partqam.accessflow.core.internal.persistence.repo.AiAnalysisRepository;
import com.partqam.accessflow.core.internal.persistence.repo.QueryRequestRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultQueryRequestLookupServiceTest {

    @Mock QueryRequestRepository queryRequestRepository;
    @Mock AiAnalysisRepository aiAnalysisRepository;
    @InjectMocks DefaultQueryRequestLookupService service;

    @Test
    void findByIdMapsFields() {
        var queryId = UUID.randomUUID();
        var datasourceId = UUID.randomUUID();
        var organizationId = UUID.randomUUID();
        var userId = UUID.randomUUID();
        var entity = entityWith(queryId, datasourceId, organizationId, userId,
                "submitter@example.com", QueryStatus.PENDING_AI);

        when(queryRequestRepository.findById(queryId)).thenReturn(Optional.of(entity));

        var result = service.findById(queryId);

        assertThat(result).isPresent();
        var snapshot = result.get();
        assertThat(snapshot.id()).isEqualTo(queryId);
        assertThat(snapshot.datasourceId()).isEqualTo(datasourceId);
        assertThat(snapshot.organizationId()).isEqualTo(organizationId);
        assertThat(snapshot.submittedByUserId()).isEqualTo(userId);
        assertThat(snapshot.sqlText()).isEqualTo("SELECT 1");
        assertThat(snapshot.queryType()).isEqualTo(QueryType.SELECT);
        assertThat(snapshot.status()).isEqualTo(QueryStatus.PENDING_AI);
    }

    @Test
    void findByIdReturnsEmptyWhenMissing() {
        var id = UUID.randomUUID();
        when(queryRequestRepository.findById(id)).thenReturn(Optional.empty());

        assertThat(service.findById(id)).isEmpty();
    }

    @Test
    void findPendingReviewReturnsViewWithoutAiAnalysisWhenAnalysisIdIsNull() {
        var queryId = UUID.randomUUID();
        var entity = entityWith(queryId, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "alice@example.com", QueryStatus.PENDING_REVIEW);
        // No AI analysis attached
        when(queryRequestRepository.findById(queryId)).thenReturn(Optional.of(entity));

        var result = service.findPendingReview(queryId);

        assertThat(result).isPresent();
        var view = result.get();
        assertThat(view.queryRequestId()).isEqualTo(queryId);
        assertThat(view.status()).isEqualTo(QueryStatus.PENDING_REVIEW);
        assertThat(view.aiAnalysisId()).isNull();
        assertThat(view.aiRiskLevel()).isNull();
        assertThat(view.aiRiskScore()).isNull();
        assertThat(view.aiSummary()).isNull();
        verifyNoInteractions(aiAnalysisRepository);
    }

    @Test
    void findPendingReviewIncludesAiAnalysisFieldsWhenLinked() {
        var queryId = UUID.randomUUID();
        var datasourceId = UUID.randomUUID();
        var organizationId = UUID.randomUUID();
        var userId = UUID.randomUUID();
        var entity = entityWith(queryId, datasourceId, organizationId, userId,
                "alice@example.com", QueryStatus.PENDING_REVIEW);
        var aiAnalysisId = UUID.randomUUID();
        entity.setAiAnalysisId(aiAnalysisId);
        var ai = new AiAnalysisEntity();
        ai.setId(aiAnalysisId);
        ai.setRiskLevel(RiskLevel.HIGH);
        ai.setRiskScore(85);
        ai.setSummary("DELETE without WHERE");

        when(queryRequestRepository.findById(queryId)).thenReturn(Optional.of(entity));
        when(aiAnalysisRepository.findById(aiAnalysisId)).thenReturn(Optional.of(ai));

        var view = service.findPendingReview(queryId).orElseThrow();

        assertThat(view.queryRequestId()).isEqualTo(queryId);
        assertThat(view.datasourceId()).isEqualTo(datasourceId);
        assertThat(view.datasourceName()).isEqualTo("ds");
        assertThat(view.organizationId()).isEqualTo(organizationId);
        assertThat(view.submittedByUserId()).isEqualTo(userId);
        assertThat(view.submittedByEmail()).isEqualTo("alice@example.com");
        assertThat(view.sqlText()).isEqualTo("SELECT 1");
        assertThat(view.queryType()).isEqualTo(QueryType.SELECT);
        assertThat(view.justification()).isEqualTo("ticket-42");
        assertThat(view.aiAnalysisId()).isEqualTo(aiAnalysisId);
        assertThat(view.aiRiskLevel()).isEqualTo(RiskLevel.HIGH);
        assertThat(view.aiRiskScore()).isEqualTo(85);
        assertThat(view.aiSummary()).isEqualTo("DELETE without WHERE");
    }

    @Test
    void findPendingReviewLeavesAiFieldsNullWhenLookupReturnsEmpty() {
        // The query points to an analysis id that doesn't resolve (orphaned link). The view
        // should still come back with null AI fields rather than throwing.
        var queryId = UUID.randomUUID();
        var entity = entityWith(queryId, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "alice@example.com", QueryStatus.PENDING_REVIEW);
        var orphanedAnalysisId = UUID.randomUUID();
        entity.setAiAnalysisId(orphanedAnalysisId);
        when(queryRequestRepository.findById(queryId)).thenReturn(Optional.of(entity));
        when(aiAnalysisRepository.findById(orphanedAnalysisId)).thenReturn(Optional.empty());

        var view = service.findPendingReview(queryId).orElseThrow();

        assertThat(view.aiAnalysisId()).isNull();
        assertThat(view.aiRiskLevel()).isNull();
        assertThat(view.aiRiskScore()).isNull();
        assertThat(view.aiSummary()).isNull();
    }

    @Test
    void findPendingReviewReturnsEmptyWhenQueryMissing() {
        var id = UUID.randomUUID();
        when(queryRequestRepository.findById(id)).thenReturn(Optional.empty());

        assertThat(service.findPendingReview(id)).isEmpty();
        verifyNoInteractions(aiAnalysisRepository);
    }

    @Test
    void findTimedOutPendingReviewIdsDelegatesToRepository() {
        var now = Instant.now();
        var id1 = UUID.randomUUID();
        var id2 = UUID.randomUUID();
        when(queryRequestRepository.findTimedOutPendingReviewIds(now))
                .thenReturn(List.of(id1, id2));

        assertThat(service.findTimedOutPendingReviewIds(now)).containsExactly(id1, id2);
    }

    @Test
    void findForOrganizationMapsListItemsWithoutAiAnalysis() {
        var orgId = UUID.randomUUID();
        var entity = entityWith(UUID.randomUUID(), UUID.randomUUID(), orgId, UUID.randomUUID(),
                "alice@example.com", QueryStatus.PENDING_AI);
        var pageable = PageRequest.of(0, 20);
        when(queryRequestRepository.findAll(any(org.springframework.data.jpa.domain.Specification.class),
                eq(pageable)))
                .thenReturn(new PageImpl<>(List.of(entity), pageable, 1));

        var page = service.findForOrganization(
                new QueryListFilter(orgId, null, null, null, null, null, null), pageable);

        assertThat(page.getTotalElements()).isEqualTo(1);
        var item = page.getContent().get(0);
        assertThat(item.id()).isEqualTo(entity.getId());
        assertThat(item.datasourceName()).isEqualTo("ds");
        assertThat(item.submittedByEmail()).isEqualTo("alice@example.com");
        assertThat(item.submittedByDisplayName()).isEqualTo("Alice");
        assertThat(item.aiRiskLevel()).isNull();
        assertThat(item.aiRiskScore()).isNull();
        assertThat(item.queryType()).isEqualTo(QueryType.SELECT);
        assertThat(item.status()).isEqualTo(QueryStatus.PENDING_AI);
    }

    @Test
    void findForOrganizationIncludesRiskFieldsWhenAiAnalysisLinked() {
        var orgId = UUID.randomUUID();
        var entity = entityWith(UUID.randomUUID(), UUID.randomUUID(), orgId, UUID.randomUUID(),
                "alice@example.com", QueryStatus.PENDING_REVIEW);
        var aiId = UUID.randomUUID();
        entity.setAiAnalysisId(aiId);
        var ai = new AiAnalysisEntity();
        ai.setId(aiId);
        ai.setRiskLevel(RiskLevel.MEDIUM);
        ai.setRiskScore(42);
        ai.setSummary("ok");

        var pageable = PageRequest.of(0, 20);
        when(queryRequestRepository.findAll(any(org.springframework.data.jpa.domain.Specification.class),
                eq(pageable)))
                .thenReturn(new PageImpl<>(List.of(entity), pageable, 1));
        when(aiAnalysisRepository.findById(aiId)).thenReturn(Optional.of(ai));

        var item = service.findForOrganization(
                new QueryListFilter(orgId, null, null, null, null, null, null), pageable)
                .getContent().get(0);

        assertThat(item.aiRiskLevel()).isEqualTo(RiskLevel.MEDIUM);
        assertThat(item.aiRiskScore()).isEqualTo(42);
    }

    @Test
    void findDetailByIdReturnsViewForOwningOrg() {
        var orgId = UUID.randomUUID();
        var queryId = UUID.randomUUID();
        var entity = entityWith(queryId, UUID.randomUUID(), orgId, UUID.randomUUID(),
                "alice@example.com", QueryStatus.EXECUTED);
        entity.setRowsAffected(5L);
        entity.setExecutionDurationMs(99);
        entity.setUpdatedAt(Instant.parse("2025-01-15T11:00:00Z"));
        var aiId = UUID.randomUUID();
        entity.setAiAnalysisId(aiId);
        var ai = new AiAnalysisEntity();
        ai.setId(aiId);
        ai.setRiskLevel(RiskLevel.LOW);
        ai.setRiskScore(10);
        ai.setSummary("looks fine");
        ai.setIssues("[]");
        ai.setMissingIndexesDetected(false);
        ai.setAffectsRowEstimate(1L);
        ai.setAiProvider(AiProviderType.ANTHROPIC);
        ai.setAiModel("claude-sonnet-4");
        ai.setPromptTokens(120);
        ai.setCompletionTokens(80);

        when(queryRequestRepository.findById(queryId)).thenReturn(Optional.of(entity));
        when(aiAnalysisRepository.findById(aiId)).thenReturn(Optional.of(ai));

        var detail = service.findDetailById(queryId, orgId).orElseThrow();

        assertThat(detail.id()).isEqualTo(queryId);
        assertThat(detail.organizationId()).isEqualTo(orgId);
        assertThat(detail.status()).isEqualTo(QueryStatus.EXECUTED);
        assertThat(detail.rowsAffected()).isEqualTo(5L);
        assertThat(detail.durationMs()).isEqualTo(99);
        assertThat(detail.aiAnalysis()).isNotNull();
        assertThat(detail.aiAnalysis().riskLevel()).isEqualTo(RiskLevel.LOW);
        assertThat(detail.aiAnalysis().aiProvider()).isEqualTo(AiProviderType.ANTHROPIC);
        assertThat(detail.aiAnalysis().promptTokens()).isEqualTo(120);
    }

    @Test
    void findDetailByIdLeavesAiAnalysisNullWhenNoAnalysisLinked() {
        var orgId = UUID.randomUUID();
        var queryId = UUID.randomUUID();
        var entity = entityWith(queryId, UUID.randomUUID(), orgId, UUID.randomUUID(),
                "alice@example.com", QueryStatus.PENDING_AI);
        when(queryRequestRepository.findById(queryId)).thenReturn(Optional.of(entity));

        var detail = service.findDetailById(queryId, orgId).orElseThrow();

        assertThat(detail.aiAnalysis()).isNull();
        verifyNoInteractions(aiAnalysisRepository);
    }

    @Test
    void findDetailByIdReturnsEmptyWhenOrgDoesNotMatch() {
        var queryId = UUID.randomUUID();
        var entity = entityWith(queryId, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "alice@example.com", QueryStatus.PENDING_AI);
        when(queryRequestRepository.findById(queryId)).thenReturn(Optional.of(entity));

        assertThat(service.findDetailById(queryId, UUID.randomUUID())).isEmpty();
    }

    @Test
    void findDetailByIdReturnsEmptyWhenQueryMissing() {
        var queryId = UUID.randomUUID();
        when(queryRequestRepository.findById(queryId)).thenReturn(Optional.empty());

        assertThat(service.findDetailById(queryId, UUID.randomUUID())).isEmpty();
    }

    @Test
    void findPendingForReviewerMapsRepositoryPageToViews() {
        var orgId = UUID.randomUUID();
        var reviewerId = UUID.randomUUID();
        var entity = entityWith(UUID.randomUUID(), UUID.randomUUID(), orgId, UUID.randomUUID(),
                "alice@example.com", QueryStatus.PENDING_REVIEW);
        var pageable = PageRequest.of(0, 20);
        when(queryRequestRepository.findPendingForReviewer(eq(orgId), eq(reviewerId),
                eq(UserRoleType.REVIEWER), any()))
                .thenReturn(new PageImpl<>(List.of(entity), pageable, 1));

        var page = service.findPendingForReviewer(orgId, reviewerId, UserRoleType.REVIEWER,
                pageable);

        assertThat(page.getTotalElements()).isEqualTo(1);
        assertThat(page.getContent()).hasSize(1);
        assertThat(page.getContent().get(0).queryRequestId()).isEqualTo(entity.getId());
        verify(queryRequestRepository).findPendingForReviewer(orgId, reviewerId,
                UserRoleType.REVIEWER, pageable);
    }

    private static QueryRequestEntity entityWith(UUID queryId, UUID datasourceId,
                                                 UUID organizationId, UUID userId, String email,
                                                 QueryStatus status) {
        var organization = new OrganizationEntity();
        organization.setId(organizationId);

        var datasource = new DatasourceEntity();
        datasource.setId(datasourceId);
        datasource.setName("ds");
        datasource.setOrganization(organization);

        var submitter = new UserEntity();
        submitter.setId(userId);
        submitter.setEmail(email);
        submitter.setDisplayName("Alice");

        var entity = new QueryRequestEntity();
        entity.setId(queryId);
        entity.setDatasource(datasource);
        entity.setSubmittedBy(submitter);
        entity.setSqlText("SELECT 1");
        entity.setQueryType(QueryType.SELECT);
        entity.setStatus(status);
        entity.setJustification("ticket-42");
        entity.setCreatedAt(Instant.parse("2025-01-15T10:00:00Z"));
        return entity;
    }
}
