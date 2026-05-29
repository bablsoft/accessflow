package com.bablsoft.accessflow.workflow.internal;

import com.bablsoft.accessflow.workflow.api.QueryTemplateFilter;
import com.bablsoft.accessflow.workflow.api.QueryTemplateVisibility;
import com.bablsoft.accessflow.workflow.internal.persistence.entity.QueryTemplateEntity;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.util.ArrayList;
import java.util.UUID;

/** Builds JPA {@link Specification}s for the query-template read endpoint. */
final class QueryTemplateSpecifications {

    private QueryTemplateSpecifications() {
    }

    /**
     * Visibility-aware filter applied to every list call. Returns templates in {@code organizationId}
     * that the caller is allowed to see: either owned by them, or {@link QueryTemplateVisibility#TEAM}.
     * The optional {@link QueryTemplateFilter} narrows further (datasource, tag, requested visibility,
     * free-text search). Ordering is {@code updated_at DESC} so the most recently touched templates
     * surface first.
     */
    static Specification<QueryTemplateEntity> forList(UUID organizationId, UUID callerUserId,
                                                     QueryTemplateFilter filter) {
        return (root, cq, cb) -> {
            cq.orderBy(cb.desc(root.get("updatedAt")));
            var predicates = new ArrayList<Predicate>();
            predicates.add(cb.equal(root.get("organizationId"), organizationId));
            predicates.add(cb.or(
                    cb.equal(root.get("ownerId"), callerUserId),
                    cb.equal(root.get("visibility"), QueryTemplateVisibility.TEAM)));
            if (filter != null) {
                if (filter.datasourceId() != null) {
                    predicates.add(cb.equal(root.get("datasourceId"), filter.datasourceId()));
                }
                if (filter.visibility() != null) {
                    predicates.add(cb.equal(root.get("visibility"), filter.visibility()));
                }
                if (filter.tag() != null && !filter.tag().isBlank()) {
                    // PostgreSQL's array_position returns the 1-based index of value in array,
                    // or NULL when not present — IS NOT NULL gives us "tag is in the array".
                    Expression<Integer> position = cb.function(
                            "array_position", Integer.class,
                            root.get("tags"), cb.literal(filter.tag()));
                    predicates.add(cb.isNotNull(position));
                }
                if (filter.search() != null && !filter.search().isBlank()) {
                    String like = "%" + filter.search().toLowerCase() + "%";
                    predicates.add(cb.or(
                            cb.like(cb.lower(root.get("name")), like),
                            cb.like(cb.lower(root.get("description")), like)));
                }
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}
