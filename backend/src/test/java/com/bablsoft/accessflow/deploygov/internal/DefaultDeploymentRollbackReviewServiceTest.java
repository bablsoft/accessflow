package com.bablsoft.accessflow.deploygov.internal;

import com.bablsoft.accessflow.audit.api.AuditAction;
import com.bablsoft.accessflow.audit.api.AuditResourceType;
import com.bablsoft.accessflow.core.api.PageRequest;
import com.bablsoft.accessflow.deploygov.api.DeploymentRollbackReviewNotFoundException;
import com.bablsoft.accessflow.deploygov.api.DeploymentRollbackReviewSelfAcknowledgeException;
import com.bablsoft.accessflow.deploygov.api.DeploymentRollbackReviewStatus;
import com.bablsoft.accessflow.deploygov.internal.persistence.entity.DeploymentRollbackReviewEntity;
import com.bablsoft.accessflow.deploygov.internal.persistence.repo.DeploymentRollbackReviewRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DefaultDeploymentRollbackReviewServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-24T12:00:00Z");
    private static final UUID ORG = UUID.randomUUID();

    private DeploymentRollbackReviewRepository repository;
    private DeploygovAuditWriter auditWriter;
    private DefaultDeploymentRollbackReviewService service;

    @BeforeEach
    void setUp() {
        repository = mock(DeploymentRollbackReviewRepository.class);
        auditWriter = mock(DeploygovAuditWriter.class);
        service = new DefaultDeploymentRollbackReviewService(repository, auditWriter,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void listWithoutStatusReturnsEveryReview() {
        var review = review();
        when(repository.findByOrganizationIdOrderByCreatedAtDesc(eq(ORG), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(review)));

        var page = service.list(ORG, null, new PageRequest(0, 20, List.of()));

        assertThat(page.content()).hasSize(1);
        assertThat(page.content().getFirst().id()).isEqualTo(review.getId());
        verify(repository, never()).findByOrganizationIdAndStatusOrderByCreatedAtDesc(any(), any(),
                any());
    }

    @Test
    void listWithStatusFilters() {
        when(repository.findByOrganizationIdAndStatusOrderByCreatedAtDesc(eq(ORG),
                eq(DeploymentRollbackReviewStatus.PENDING_REVIEW), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(review())));

        var page = service.list(ORG, DeploymentRollbackReviewStatus.PENDING_REVIEW,
                new PageRequest(0, 20, List.of()));

        assertThat(page.content()).hasSize(1);
    }

    @Test
    void getReturnsTheView() {
        var review = review();
        when(repository.findByIdAndOrganizationId(review.getId(), ORG))
                .thenReturn(Optional.of(review));

        var view = service.get(review.getId(), ORG);

        assertThat(view.id()).isEqualTo(review.getId());
        assertThat(view.status()).isEqualTo(DeploymentRollbackReviewStatus.PENDING_REVIEW);
    }

    @Test
    void getThrowsNotFoundForACrossOrgId() {
        var id = UUID.randomUUID();
        when(repository.findByIdAndOrganizationId(id, ORG)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(id, ORG))
                .isInstanceOf(DeploymentRollbackReviewNotFoundException.class);
    }

    @Test
    void acknowledgeClosesTheReviewAndAudits() {
        var review = review();
        var reviewer = UUID.randomUUID();
        when(repository.findByIdAndOrganizationId(review.getId(), ORG))
                .thenReturn(Optional.of(review));

        var view = service.acknowledge(review.getId(), ORG, reviewer, "ack");

        assertThat(view.status()).isEqualTo(DeploymentRollbackReviewStatus.REVIEWED);
        assertThat(view.reviewedBy()).isEqualTo(reviewer);
        assertThat(view.reviewComment()).isEqualTo("ack");
        assertThat(view.reviewedAt()).isEqualTo(NOW);
        verify(repository).save(review);
        verify(auditWriter).record(eq(AuditAction.DEPLOYMENT_ROLLBACK_REVIEWED),
                eq(AuditResourceType.DEPLOYMENT_ROLLBACK_REVIEW), eq(review.getId()), eq(ORG),
                eq(reviewer), any(), any());
    }

    @Test
    void submitterCannotAcknowledgeTheirOwnRollback() {
        var review = review();
        when(repository.findByIdAndOrganizationId(review.getId(), ORG))
                .thenReturn(Optional.of(review));

        assertThatThrownBy(() -> service.acknowledge(review.getId(), ORG, review.getSubmittedBy(),
                null))
                .isInstanceOf(DeploymentRollbackReviewSelfAcknowledgeException.class);
        verify(repository, never()).save(any());
    }

    @Test
    void repeatAcknowledgeIsANoOp() {
        var review = review();
        review.setStatus(DeploymentRollbackReviewStatus.REVIEWED);
        review.setReviewedBy(UUID.randomUUID());
        review.setReviewedAt(NOW.minusSeconds(60));
        when(repository.findByIdAndOrganizationId(review.getId(), ORG))
                .thenReturn(Optional.of(review));

        var view = service.acknowledge(review.getId(), ORG, UUID.randomUUID(), "late");

        assertThat(view.reviewedAt()).isEqualTo(NOW.minusSeconds(60));
        verify(repository, never()).save(any());
        verify(auditWriter, never()).record(any(), any(), any(), any(), any(), any(), any());
    }

    private DeploymentRollbackReviewEntity review() {
        var entity = new DeploymentRollbackReviewEntity();
        entity.setId(UUID.randomUUID());
        entity.setDeploymentRequestId(UUID.randomUUID());
        entity.setOrganizationId(ORG);
        entity.setPipelineId(UUID.randomUUID());
        entity.setEnvironmentId(UUID.randomUUID());
        entity.setSubmittedBy(UUID.randomUUID());
        return entity;
    }
}
