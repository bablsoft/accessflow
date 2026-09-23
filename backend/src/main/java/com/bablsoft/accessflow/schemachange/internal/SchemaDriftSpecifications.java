package com.bablsoft.accessflow.schemachange.internal;

import com.bablsoft.accessflow.schemachange.api.SchemaDriftFindingListFilter;
import com.bablsoft.accessflow.schemachange.api.SchemaDriftScanListFilter;
import com.bablsoft.accessflow.schemachange.internal.persistence.entity.SchemaDriftFindingEntity;
import com.bablsoft.accessflow.schemachange.internal.persistence.entity.SchemaDriftScanEntity;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.util.ArrayList;
import java.util.UUID;

/**
 * Listing filters for drift scans and findings (#881). Each optional predicate is added only when
 * its filter field is set — a JPQL {@code (:status is null or f.status = :status)} against a PG enum
 * column fails at runtime with "could not determine data type of parameter", and this is the shape
 * that sidesteps it.
 */
final class SchemaDriftSpecifications {

    private SchemaDriftSpecifications() {
    }

    static Specification<SchemaDriftScanEntity> scans(UUID organizationId, SchemaDriftScanListFilter filter) {
        return (root, cq, cb) -> {
            cq.orderBy(cb.desc(root.get("startedAt")), cb.asc(root.get("id")));
            var predicates = new ArrayList<Predicate>();
            predicates.add(cb.equal(root.get("organizationId"), organizationId));
            if (filter != null && filter.pipelineId() != null) {
                predicates.add(cb.equal(root.get("pipelineId"), filter.pipelineId()));
            }
            if (filter != null && filter.environmentId() != null) {
                predicates.add(cb.equal(root.get("environmentId"), filter.environmentId()));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }

    static Specification<SchemaDriftFindingEntity> findings(UUID organizationId,
                                                            SchemaDriftFindingListFilter filter) {
        return (root, cq, cb) -> {
            cq.orderBy(cb.desc(root.get("lastSeenAt")), cb.asc(root.get("id")));
            var predicates = new ArrayList<Predicate>();
            predicates.add(cb.equal(root.get("organizationId"), organizationId));
            if (filter != null && filter.environmentId() != null) {
                predicates.add(cb.equal(root.get("environmentId"), filter.environmentId()));
            }
            if (filter != null && filter.status() != null) {
                predicates.add(cb.equal(root.get("status"), filter.status()));
            }
            if (filter != null && filter.pipelineId() != null) {
                // Joined through the owning scan rather than resolved through deploygov: findings
                // outlive the environments they name (bare UUID, no FK), and the join keeps them
                // visible after an environment is deleted.
                predicates.add(cb.equal(root.join("scan").get("pipelineId"), filter.pipelineId()));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}
