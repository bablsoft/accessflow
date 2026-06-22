package com.bablsoft.accessflow.workflow.internal;

import com.bablsoft.accessflow.workflow.api.BreakGlassEventFilter;
import com.bablsoft.accessflow.workflow.internal.persistence.entity.BreakGlassEventEntity;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.util.ArrayList;
import java.util.UUID;

/** Builds JPA {@link Specification}s for the admin break-glass log list endpoint (AF-385). */
final class BreakGlassEventSpecifications {

    private BreakGlassEventSpecifications() {
    }

    static Specification<BreakGlassEventEntity> forQuery(UUID organizationId,
                                                         BreakGlassEventFilter filter) {
        return (root, cq, cb) -> {
            var predicates = new ArrayList<Predicate>();
            predicates.add(cb.equal(root.get("organizationId"), organizationId));
            if (filter.status() != null) {
                predicates.add(cb.equal(root.get("status"), filter.status()));
            }
            if (filter.datasourceId() != null) {
                predicates.add(cb.equal(root.get("datasourceId"), filter.datasourceId()));
            }
            if (filter.submittedByUserId() != null) {
                predicates.add(cb.equal(root.get("submittedBy"), filter.submittedByUserId()));
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
}
