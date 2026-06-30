package com.bablsoft.accessflow.apigov.internal;

import com.bablsoft.accessflow.apigov.api.ApiRequestListFilter;
import com.bablsoft.accessflow.apigov.internal.persistence.entity.ApiRequestEntity;
import com.bablsoft.accessflow.core.api.QueryStatus;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.util.ArrayList;
import java.util.UUID;

final class ApiRequestSpecifications {

    private ApiRequestSpecifications() {
    }

    static Specification<ApiRequestEntity> forFilter(ApiRequestListFilter filter) {
        return (root, cq, cb) -> {
            cq.orderBy(cb.desc(root.get("createdAt")));
            var predicates = new ArrayList<Predicate>();
            predicates.add(cb.equal(root.get("organizationId"), filter.organizationId()));
            if (filter.submittedByUserId() != null) {
                predicates.add(cb.equal(root.get("submittedBy"), filter.submittedByUserId()));
            }
            if (filter.connectorId() != null) {
                predicates.add(cb.equal(root.get("connectorId"), filter.connectorId()));
            }
            if (filter.status() != null) {
                predicates.add(cb.equal(root.get("status"), filter.status()));
            }
            if (filter.verb() != null && !filter.verb().isBlank()) {
                predicates.add(cb.equal(root.get("verb"), filter.verb()));
            }
            if (filter.traceId() != null && !filter.traceId().isBlank()) {
                predicates.add(cb.equal(root.get("traceId"), filter.traceId().trim()));
            }
            if (filter.spanId() != null && !filter.spanId().isBlank()) {
                predicates.add(cb.equal(root.get("spanId"), filter.spanId().trim()));
            }
            if (filter.from() != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), filter.from()));
            }
            if (filter.to() != null) {
                predicates.add(cb.lessThan(root.get("createdAt"), filter.to()));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }

    /**
     * Pending-review queue for one reviewer: org-scoped, fixed to {@code PENDING_REVIEW}, and excludes
     * the reviewer's own submissions (self-approval is forbidden) so the page count is accurate.
     */
    static Specification<ApiRequestEntity> forPendingReview(UUID organizationId, UUID reviewerId,
                                                            UUID connectorId, String verb) {
        return (root, cq, cb) -> {
            cq.orderBy(cb.desc(root.get("createdAt")));
            var predicates = new ArrayList<Predicate>();
            predicates.add(cb.equal(root.get("organizationId"), organizationId));
            predicates.add(cb.equal(root.get("status"), QueryStatus.PENDING_REVIEW));
            predicates.add(cb.notEqual(root.get("submittedBy"), reviewerId));
            if (connectorId != null) {
                predicates.add(cb.equal(root.get("connectorId"), connectorId));
            }
            if (verb != null && !verb.isBlank()) {
                predicates.add(cb.equal(root.get("verb"), verb));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}
