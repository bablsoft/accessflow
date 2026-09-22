package com.bablsoft.accessflow.schemachange.internal;

import com.bablsoft.accessflow.schemachange.api.SchemaChangeSetListFilter;
import com.bablsoft.accessflow.schemachange.internal.persistence.entity.SchemaChangeSetEntity;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.util.ArrayList;
import java.util.UUID;

/**
 * Listing filter for change sets (#879). Each optional predicate is added only when its filter
 * field is set — a JPQL {@code (:status is null or s.status = :status)} against the PG enum column
 * fails with "could not determine data type of parameter" (the {@code GrantUsageSpecifications}
 * precedent), and this is the shape that sidesteps it.
 */
final class SchemaChangeSetSpecifications {

    private SchemaChangeSetSpecifications() {
    }

    static Specification<SchemaChangeSetEntity> forFilter(UUID organizationId, SchemaChangeSetListFilter filter) {
        return (root, cq, cb) -> {
            cq.orderBy(cb.desc(root.get("createdAt")), cb.asc(root.get("id")));
            var predicates = new ArrayList<Predicate>();
            predicates.add(cb.equal(root.get("organizationId"), organizationId));
            if (filter != null && filter.pipelineId() != null) {
                predicates.add(cb.equal(root.get("pipelineId"), filter.pipelineId()));
            }
            if (filter != null && filter.status() != null) {
                predicates.add(cb.equal(root.get("status"), filter.status()));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}
