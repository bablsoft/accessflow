package com.bablsoft.accessflow.deploygov.internal;

import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.deploygov.api.DeploymentRequestListFilter;
import com.bablsoft.accessflow.deploygov.internal.persistence.entity.DeploymentRequestEntity;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Set;
import java.util.UUID;

final class DeploymentRequestSpecifications {

    private DeploymentRequestSpecifications() {
    }

    /**
     * AND-combines the non-null filter fields, newest first. The {@code environment} filter is a
     * name, which the service resolves to ids before calling this — an empty {@code environmentIds}
     * when a name <em>was</em> supplied means the name matched nothing, which must page as empty
     * rather than unfiltered.
     */
    static Specification<DeploymentRequestEntity> forFilter(DeploymentRequestListFilter filter,
                                                            Collection<UUID> environmentIds) {
        var environmentFilterRequested = filter.environment() != null && !filter.environment().isBlank();
        return (root, cq, cb) -> {
            cq.orderBy(cb.desc(root.get("createdAt")));
            if (environmentFilterRequested && environmentIds.isEmpty()) {
                return cb.disjunction();
            }
            var predicates = new ArrayList<Predicate>();
            predicates.add(cb.equal(root.get("organizationId"), filter.organizationId()));
            if (filter.submittedByUserId() != null) {
                predicates.add(cb.equal(root.get("submittedBy"), filter.submittedByUserId()));
            }
            if (filter.pipelineId() != null) {
                predicates.add(cb.equal(root.get("pipelineId"), filter.pipelineId()));
            }
            if (environmentFilterRequested) {
                predicates.add(root.get("environmentId").in(environmentIds));
            }
            if (filter.version() != null && !filter.version().isBlank()) {
                predicates.add(cb.equal(root.get("version"), filter.version().trim()));
            }
            if (filter.status() != null) {
                predicates.add(cb.equal(root.get("status"), filter.status()));
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
     * Pending-review queue for one reviewer (#692): org-scoped, fixed to {@code PENDING_REVIEW},
     * and excludes the reviewer's own submissions (self-approval is forbidden) so the page count
     * is accurate. Approver eligibility arrives pre-resolved as the set of environment ids whose
     * review plan the caller may approve — deploygov cannot reference {@code core}'s approver
     * entities to compute it in SQL. Unlike the apigov sibling there is a single identity: review
     * delegation (#622) is not supported for deployments.
     *
     * @param unrestricted true when the caller holds {@code REVIEW_OVERRIDE} and no reach applies
     */
    static Specification<DeploymentRequestEntity> forPendingReview(UUID organizationId,
                                                                   UUID reviewerId, UUID pipelineId,
                                                                   boolean unrestricted,
                                                                   Set<UUID> reachableEnvironmentIds) {
        return (root, cq, cb) -> {
            cq.orderBy(cb.desc(root.get("createdAt")));
            var predicates = new ArrayList<Predicate>();
            predicates.add(cb.equal(root.get("organizationId"), organizationId));
            predicates.add(cb.equal(root.get("status"), QueryStatus.PENDING_REVIEW));
            predicates.add(cb.notEqual(root.get("submittedBy"), reviewerId));
            if (pipelineId != null) {
                predicates.add(cb.equal(root.get("pipelineId"), pipelineId));
            }
            if (!unrestricted) {
                if (reachableEnvironmentIds.isEmpty()) {
                    // Eligible for nothing — an always-false predicate, not an unfiltered page.
                    return cb.disjunction();
                }
                predicates.add(root.get("environmentId").in(reachableEnvironmentIds));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}
