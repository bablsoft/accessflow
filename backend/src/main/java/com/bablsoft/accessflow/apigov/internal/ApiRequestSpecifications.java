package com.bablsoft.accessflow.apigov.internal;

import com.bablsoft.accessflow.apigov.api.ApiRequestListFilter;
import com.bablsoft.accessflow.apigov.internal.persistence.entity.ApiRequestEntity;
import com.bablsoft.accessflow.core.api.QueryStatus;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
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
     * The connectors one identity may review, and — for a borrowed identity — the delegator whose
     * submissions that identity must not be used against (#622).
     */
    record ReviewReach(UUID onBehalfOfUserId, Set<UUID> connectorIds) {
    }

    /**
     * Pending-review queue for one reviewer: org-scoped, fixed to {@code PENDING_REVIEW}, and excludes
     * the reviewer's own submissions (self-approval is forbidden) so the page count is accurate.
     *
     * <p>Unlike the query-review queue this specification must be <strong>exact</strong> — there is
     * no in-memory re-filter behind it. Approver eligibility therefore arrives pre-resolved as one
     * {@link ReviewReach} per identity, because {@code apigov} cannot reference {@code core}'s
     * approver entities to compute it in SQL. Each borrowed identity carries its own submitter
     * exclusion, so the delegator-is-submitter rule is enforced per-identity.
     *
     * <p>The {@code reviewerId} exclusion stays a scalar {@code notEqual}. Widening it to cover the
     * reviewer's delegators would hide requests they are eligible for in their own right.
     *
     * @param unrestricted true when the caller holds {@code REVIEW_OVERRIDE} and no reach applies
     */
    static Specification<ApiRequestEntity> forPendingReview(UUID organizationId, UUID reviewerId,
                                                            UUID connectorId, String verb,
                                                            boolean unrestricted,
                                                            List<ReviewReach> reaches) {
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
            if (!unrestricted) {
                var branches = new ArrayList<Predicate>();
                for (var reach : reaches) {
                    if (reach.connectorIds().isEmpty()) {
                        continue;
                    }
                    Predicate reachable = root.get("connectorId").in(reach.connectorIds());
                    branches.add(reach.onBehalfOfUserId() == null
                            ? reachable
                            : cb.and(reachable, cb.notEqual(root.get("submittedBy"),
                                    reach.onBehalfOfUserId())));
                }
                if (branches.isEmpty()) {
                    // Eligible for nothing — an always-false predicate rather than an unfiltered page.
                    return cb.disjunction();
                }
                predicates.add(cb.or(branches.toArray(new Predicate[0])));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}
