package com.bablsoft.accessflow.deploygov.internal;

import com.bablsoft.accessflow.audit.api.AuditAction;
import com.bablsoft.accessflow.audit.api.AuditResourceType;
import com.bablsoft.accessflow.core.api.PageRequest;
import com.bablsoft.accessflow.core.api.PageResponse;
import com.bablsoft.accessflow.deploygov.api.DeploymentRollbackReviewNotFoundException;
import com.bablsoft.accessflow.deploygov.api.DeploymentRollbackReviewSelfAcknowledgeException;
import com.bablsoft.accessflow.deploygov.api.DeploymentRollbackReviewService;
import com.bablsoft.accessflow.deploygov.api.DeploymentRollbackReviewStatus;
import com.bablsoft.accessflow.deploygov.api.DeploymentRollbackReviewView;
import com.bablsoft.accessflow.deploygov.internal.persistence.entity.DeploymentRollbackReviewEntity;
import com.bablsoft.accessflow.deploygov.internal.persistence.repo.DeploymentRollbackReviewRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Map;
import java.util.UUID;

/**
 * The rollback follow-up worklist (#693). Acknowledgment is a latch: the first reviewer to
 * acknowledge closes the record, a repeat acknowledge is a no-op returning the current state, and
 * the deployment's submitter can never acknowledge their own rollback — the same "never the
 * submitter" rule as the break-glass retro-review.
 */
@Service
@RequiredArgsConstructor
public class DefaultDeploymentRollbackReviewService implements DeploymentRollbackReviewService {

    private final DeploymentRollbackReviewRepository reviewRepository;
    private final DeploygovAuditWriter auditWriter;
    private final Clock clock;

    @Override
    @Transactional(readOnly = true)
    public PageResponse<DeploymentRollbackReviewView> list(UUID organizationId,
                                                           DeploymentRollbackReviewStatus status,
                                                           PageRequest pageRequest) {
        var pageable = toPageable(pageRequest);
        var page = status == null
                ? reviewRepository.findByOrganizationIdOrderByCreatedAtDesc(organizationId, pageable)
                : reviewRepository.findByOrganizationIdAndStatusOrderByCreatedAtDesc(
                        organizationId, status, pageable);
        return toPage(page);
    }

    @Override
    @Transactional(readOnly = true)
    public DeploymentRollbackReviewView get(UUID id, UUID organizationId) {
        return toView(require(id, organizationId));
    }

    @Override
    @Transactional
    public DeploymentRollbackReviewView acknowledge(UUID id, UUID organizationId, UUID reviewerId,
                                                    String comment) {
        var review = require(id, organizationId);
        if (review.getSubmittedBy().equals(reviewerId)) {
            throw new DeploymentRollbackReviewSelfAcknowledgeException(id);
        }
        if (review.getStatus() == DeploymentRollbackReviewStatus.REVIEWED) {
            return toView(review);
        }
        review.setStatus(DeploymentRollbackReviewStatus.REVIEWED);
        review.setReviewedBy(reviewerId);
        review.setReviewComment(comment);
        review.setReviewedAt(clock.instant());
        reviewRepository.save(review);
        auditWriter.record(AuditAction.DEPLOYMENT_ROLLBACK_REVIEWED,
                AuditResourceType.DEPLOYMENT_ROLLBACK_REVIEW, review.getId(), organizationId,
                reviewerId,
                Map.of("deployment_request_id", review.getDeploymentRequestId().toString(),
                        "pipeline_id", review.getPipelineId().toString(),
                        "submitted_by", review.getSubmittedBy().toString()),
                null);
        return toView(review);
    }

    private DeploymentRollbackReviewEntity require(UUID id, UUID organizationId) {
        return reviewRepository.findByIdAndOrganizationId(id, organizationId)
                .orElseThrow(() -> new DeploymentRollbackReviewNotFoundException(id));
    }

    private PageResponse<DeploymentRollbackReviewView> toPage(
            Page<DeploymentRollbackReviewEntity> page) {
        var content = page.getContent().stream().map(this::toView).toList();
        return new PageResponse<>(content, page.getNumber(),
                page.getSize() <= 0 ? 1 : page.getSize(), page.getTotalElements(),
                page.getTotalPages());
    }

    private DeploymentRollbackReviewView toView(DeploymentRollbackReviewEntity entity) {
        return new DeploymentRollbackReviewView(entity.getId(), entity.getDeploymentRequestId(),
                entity.getOrganizationId(), entity.getPipelineId(), entity.getEnvironmentId(),
                entity.getSubmittedBy(), entity.getOutcomeDetail(), entity.getStatus(),
                entity.getReviewedBy(), entity.getReviewComment(), entity.getReviewedAt(),
                entity.getCreatedAt());
    }

    private static Pageable toPageable(PageRequest pageRequest) {
        return org.springframework.data.domain.PageRequest.of(pageRequest.page(),
                pageRequest.size());
    }
}
