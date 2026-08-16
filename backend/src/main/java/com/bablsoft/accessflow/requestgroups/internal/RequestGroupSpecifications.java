package com.bablsoft.accessflow.requestgroups.internal;

import com.bablsoft.accessflow.requestgroups.api.RequestGroupListFilter;
import com.bablsoft.accessflow.requestgroups.api.RequestGroupStatus;
import com.bablsoft.accessflow.requestgroups.internal.persistence.entity.RequestGroupEntity;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.util.ArrayList;
import java.util.UUID;

final class RequestGroupSpecifications {

    private RequestGroupSpecifications() {
    }

    static Specification<RequestGroupEntity> forFilter(RequestGroupListFilter filter) {
        return (root, cq, cb) -> {
            cq.orderBy(cb.desc(root.get("createdAt")));
            var predicates = new ArrayList<Predicate>();
            predicates.add(cb.equal(root.get("organizationId"), filter.organizationId()));
            if (filter.submittedByUserId() != null) {
                predicates.add(cb.equal(root.get("submittedBy"), filter.submittedByUserId()));
            }
            if (filter.status() != null) {
                predicates.add(cb.equal(root.get("status"), filter.status()));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }

    /**
     * Pending-review queue for one reviewer: org-scoped, PENDING_REVIEW, excludes own submissions.
     *
     * <p>Deliberately an over-approximation — approver eligibility is a union over each member's
     * review plan, which no flat predicate can express, so {@code DefaultGroupReviewService
     * .listPending} filters the page per-caller afterwards. Do not "optimise away" that filter.
     *
     * <p>The {@code submittedBy} exclusion must stay a scalar {@code notEqual} on the acting user.
     * Widening it to exclude the reviewer's delegators (#622) would hide every request those
     * delegators submitted, including ones the reviewer is eligible for in their own right — that
     * exclusion is per-identity and belongs in the in-memory pass.
     */
    static Specification<RequestGroupEntity> forPendingReview(UUID organizationId, UUID reviewerId) {
        return (root, cq, cb) -> {
            cq.orderBy(cb.desc(root.get("createdAt")));
            var predicates = new ArrayList<Predicate>();
            predicates.add(cb.equal(root.get("organizationId"), organizationId));
            predicates.add(cb.equal(root.get("status"), RequestGroupStatus.PENDING_REVIEW));
            predicates.add(cb.notEqual(root.get("submittedBy"), reviewerId));
            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}
