package com.bablsoft.accessflow.ai.internal;

import com.bablsoft.accessflow.ai.api.AnomalyListFilter;
import com.bablsoft.accessflow.ai.internal.persistence.entity.BehaviorAnomalyEntity;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.util.ArrayList;
import java.util.UUID;

/** Builds JPA {@link Specification}s for the admin behaviour-anomaly list endpoint (AF-383). */
final class BehaviorAnomalySpecifications {

    private BehaviorAnomalySpecifications() {
    }

    static Specification<BehaviorAnomalyEntity> forQuery(UUID organizationId, AnomalyListFilter filter) {
        return (root, cq, cb) -> {
            var predicates = new ArrayList<Predicate>();
            predicates.add(cb.equal(root.get("organizationId"), organizationId));
            if (filter.userId() != null) {
                predicates.add(cb.equal(root.get("userId"), filter.userId()));
            }
            if (filter.datasourceId() != null) {
                predicates.add(cb.equal(root.get("datasourceId"), filter.datasourceId()));
            }
            if (filter.feature() != null && !filter.feature().isBlank()) {
                predicates.add(cb.equal(root.get("feature"), filter.feature()));
            }
            if (filter.status() != null) {
                predicates.add(cb.equal(root.get("status"), filter.status()));
            }
            if (filter.from() != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("detectedAt"), filter.from()));
            }
            if (filter.to() != null) {
                predicates.add(cb.lessThan(root.get("detectedAt"), filter.to()));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}
