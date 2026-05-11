package com.partqam.accessflow.core.internal;

import com.partqam.accessflow.core.api.AiProviderType;
import com.partqam.accessflow.core.api.DecisionType;
import com.partqam.accessflow.core.api.QueryListFilter;
import com.partqam.accessflow.core.api.QueryStatus;
import com.partqam.accessflow.core.api.QueryType;
import com.partqam.accessflow.core.api.RiskLevel;
import com.partqam.accessflow.core.api.UserRoleType;
import com.partqam.accessflow.core.internal.persistence.entity.AiAnalysisEntity;
import com.partqam.accessflow.core.internal.persistence.entity.DatasourceEntity;
import com.partqam.accessflow.core.internal.persistence.entity.OrganizationEntity;
import com.partqam.accessflow.core.internal.persistence.entity.QueryRequestEntity;
import com.partqam.accessflow.core.internal.persistence.entity.ReviewDecisionEntity;
import com.partqam.accessflow.core.internal.persistence.entity.UserEntity;
import com.partqam.accessflow.core.internal.persistence.repo.AiAnalysisRepository;
import com.partqam.accessflow.core.internal.persistence.repo.QueryRequestRepository;
import com.partqam.accessflow.core.internal.persistence.repo.ReviewDecisionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultQueryRequestLookupServiceTest {

    @Mock QueryRequestRepository queryRequestRepository;
    @Mock AiAnalysisRepository aiAnalysisRepository;
    @Mock ReviewDecisionRepository reviewDecisionRepository;
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
        when(reviewDecisionRepository.findAllByQueryRequest_IdOrderByDecidedAtAsc(queryId))
                .thenReturn(List.of());

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
        assertThat(detail.reviewDecisions()).isEmpty();
    }

    @Test
    void findDetailByIdLeavesAiAnalysisNullWhenNoAnalysisLinked() {
        var orgId = UUID.randomUUID();
        var queryId = UUID.randomUUID();
        var entity = entityWith(queryId, UUID.randomUUID(), orgId, UUID.randomUUID(),
                "alice@example.com", QueryStatus.PENDING_AI);
        when(queryRequestRepository.findById(queryId)).thenReturn(Optional.of(entity));
        when(reviewDecisionRepository.findAllByQueryRequest_IdOrderByDecidedAtAsc(queryId))
                .thenReturn(List.of());

        var detail = service.findDetailById(queryId, orgId).orElseThrow();

        assertThat(detail.aiAnalysis()).isNull();
        verifyNoInteractions(aiAnalysisRepository);
    }

    @Test
    void findDetailByIdMapsReviewDecisionsWithReviewerRefInDecidedAtOrder() {
        var orgId = UUID.randomUUID();
        var queryId = UUID.randomUUID();
        var entity = entityWith(queryId, UUID.randomUUID(), orgId, UUID.randomUUID(),
                "alice@example.com", QueryStatus.REJECTED);
        when(queryRequestRepository.findById(queryId)).thenReturn(Optional.of(entity));

        var reviewer1 = newReviewer("first@example.com", "First Reviewer");
        var reviewer2 = newReviewer("second@example.com", "");
        var decision1 = newDecision(entity, reviewer1, DecisionType.REQUESTED_CHANGES,
                "needs LIMIT", 1, Instant.parse("2025-01-15T10:30:00Z"));
        var decision2 = newDecision(entity, reviewer2, DecisionType.REJECTED,
                "still unsafe", 2, Instant.parse("2025-01-15T11:15:00Z"));
        when(reviewDecisionRepository.findAllByQueryRequest_IdOrderByDecidedAtAsc(queryId))
                .thenReturn(List.of(decision1, decision2));

        var detail = service.findDetailById(queryId, orgId).orElseThrow();

        assertThat(detail.reviewDecisions()).hasSize(2);
        var first = detail.reviewDecisions().get(0);
        assertThat(first.id()).isEqualTo(decision1.getId());
        assertThat(first.reviewer().id()).isEqualTo(reviewer1.getId());
        assertThat(first.reviewer().email()).isEqualTo("first@example.com");
        assertThat(first.reviewer().displayName()).isEqualTo("First Reviewer");
        assertThat(first.decision()).isEqualTo(DecisionType.REQUESTED_CHANGES);
        assertThat(first.comment()).isEqualTo("needs LIMIT");
        assertThat(first.stage()).isEqualTo(1);
        assertThat(first.decidedAt()).isEqualTo(Instant.parse("2025-01-15T10:30:00Z"));

        var second = detail.reviewDecisions().get(1);
        assertThat(second.reviewer().email()).isEqualTo("second@example.com");
        assertThat(second.reviewer().displayName()).isEmpty();
        assertThat(second.decision()).isEqualTo(DecisionType.REJECTED);
        assertThat(second.stage()).isEqualTo(2);
    }

    private static UserEntity newReviewer(String email, String displayName) {
        var user = new UserEntity();
        user.setId(UUID.randomUUID());
        user.setEmail(email);
        user.setDisplayName(displayName);
        return user;
    }

    private static ReviewDecisionEntity newDecision(QueryRequestEntity query, UserEntity reviewer,
                                                    DecisionType decision, String comment,
                                                    int stage, Instant decidedAt) {
        var entity = new ReviewDecisionEntity();
        entity.setId(UUID.randomUUID());
        entity.setQueryRequest(query);
        entity.setReviewer(reviewer);
        entity.setDecision(decision);
        entity.setComment(comment);
        entity.setStage(stage);
        entity.setDecidedAt(decidedAt);
        return entity;
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
    void countForOrganizationDelegatesToRepositorySpecCount() {
        var orgId = UUID.randomUUID();
        var filter = new QueryListFilter(orgId, null, null, null, null, null, null);
        when(queryRequestRepository.count(any(org.springframework.data.jpa.domain.Specification.class)))
                .thenReturn(42L);

        assertThat(service.countForOrganization(filter)).isEqualTo(42L);
        verify(queryRequestRepository)
                .count(any(org.springframework.data.jpa.domain.Specification.class));
    }

    @Test
    void streamForOrganizationEmitsAllRowsWhenUnderCap() {
        var orgId = UUID.randomUUID();
        var filter = new QueryListFilter(orgId, null, null, null, null, null, null);
        var e1 = entityWith(UUID.randomUUID(), UUID.randomUUID(), orgId, UUID.randomUUID(),
                "a@example.com", QueryStatus.EXECUTED);
        var e2 = entityWith(UUID.randomUUID(), UUID.randomUUID(), orgId, UUID.randomUUID(),
                "b@example.com", QueryStatus.PENDING_AI);

        // Single page returned by the repository — no further pages.
        when(queryRequestRepository.findAll(any(org.springframework.data.jpa.domain.Specification.class),
                any(org.springframework.data.domain.Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(e1, e2), PageRequest.of(0, 500), 2));

        var collected = new ArrayList<UUID>();
        service.streamForOrganization(filter, 100, view -> collected.add(view.id()));

        assertThat(collected).containsExactly(e1.getId(), e2.getId());
    }

    @Test
    void streamForOrganizationStopsAtMaxRows() {
        var orgId = UUID.randomUUID();
        var filter = new QueryListFilter(orgId, null, null, null, null, null, null);
        var e1 = entityWith(UUID.randomUUID(), UUID.randomUUID(), orgId, UUID.randomUUID(),
                "a@example.com", QueryStatus.EXECUTED);
        var e2 = entityWith(UUID.randomUUID(), UUID.randomUUID(), orgId, UUID.randomUUID(),
                "b@example.com", QueryStatus.PENDING_AI);
        var e3 = entityWith(UUID.randomUUID(), UUID.randomUUID(), orgId, UUID.randomUUID(),
                "c@example.com", QueryStatus.PENDING_REVIEW);

        // Repository returns three rows on a single page, but the service should only emit two.
        when(queryRequestRepository.findAll(any(org.springframework.data.jpa.domain.Specification.class),
                any(org.springframework.data.domain.Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(e1, e2, e3), PageRequest.of(0, 2), 3));

        var collected = new ArrayList<UUID>();
        service.streamForOrganization(filter, 2, view -> collected.add(view.id()));

        assertThat(collected).containsExactly(e1.getId(), e2.getId());
        // Only one repository call: the in-loop check on `emitted >= maxRows` short-circuits
        // before pagination would advance.
        verify(queryRequestRepository, times(1)).findAll(
                any(org.springframework.data.jpa.domain.Specification.class),
                any(org.springframework.data.domain.Pageable.class));
    }

    @Test
    void streamForOrganizationEmitsNothingWhenMaxRowsZero() {
        var orgId = UUID.randomUUID();
        var filter = new QueryListFilter(orgId, null, null, null, null, null, null);

        var collected = new ArrayList<UUID>();
        service.streamForOrganization(filter, 0, view -> collected.add(view.id()));

        assertThat(collected).isEmpty();
        verifyNoInteractions(queryRequestRepository);
    }

    @Test
    void streamForOrganizationAdvancesAcrossPagesUntilExhausted() {
        var orgId = UUID.randomUUID();
        var filter = new QueryListFilter(orgId, null, null, null, null, null, null);
        var e1 = entityWith(UUID.randomUUID(), UUID.randomUUID(), orgId, UUID.randomUUID(),
                "a@example.com", QueryStatus.EXECUTED);
        var e2 = entityWith(UUID.randomUUID(), UUID.randomUUID(), orgId, UUID.randomUUID(),
                "b@example.com", QueryStatus.PENDING_AI);

        // First page reports hasNext=true (totalElements=2 on a page of 1); second page returns
        // the remaining row and reports hasNext=false. Use a generous totalElements so PageImpl
        // computes hasNext correctly.
        when(queryRequestRepository.findAll(any(org.springframework.data.jpa.domain.Specification.class),
                any(org.springframework.data.domain.Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(e1), PageRequest.of(0, 1), 2))
                .thenReturn(new PageImpl<>(List.of(e2), PageRequest.of(1, 1), 2));

        var collected = new ArrayList<UUID>();
        service.streamForOrganization(filter, 100, view -> collected.add(view.id()));

        assertThat(collected).containsExactly(e1.getId(), e2.getId());
        verify(queryRequestRepository, times(2)).findAll(
                any(org.springframework.data.jpa.domain.Specification.class),
                any(org.springframework.data.domain.Pageable.class));
    }

    @Test
    void findPendingForReviewerMapsRepositoryPageToViews() {
        var orgId = UUID.randomUUID();
        var reviewerId = UUID.randomUUID();
        var entity = entityWith(UUID.randomUUID(), UUID.randomUUID(), orgId, UUID.randomUUID(),
                "alice@example.com", QueryStatus.PENDING_REVIEW);
        var pageable = PageRequest.of(0, 20);
        when(queryRequestRepository.findPendingForReviewer(eq(orgId), eq(reviewerId),
                eq(UserRoleType.REVIEWER), eq(QueryStatus.PENDING_REVIEW), any()))
                .thenReturn(new PageImpl<>(List.of(entity), pageable, 1));

        var page = service.findPendingForReviewer(orgId, reviewerId, UserRoleType.REVIEWER,
                pageable);

        assertThat(page.getTotalElements()).isEqualTo(1);
        assertThat(page.getContent()).hasSize(1);
        assertThat(page.getContent().get(0).queryRequestId()).isEqualTo(entity.getId());
        verify(queryRequestRepository).findPendingForReviewer(orgId, reviewerId,
                UserRoleType.REVIEWER, QueryStatus.PENDING_REVIEW, pageable);
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
