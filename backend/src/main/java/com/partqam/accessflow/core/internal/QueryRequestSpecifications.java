package com.partqam.accessflow.core.internal;

import com.partqam.accessflow.core.api.QueryListFilter;
import com.partqam.accessflow.core.internal.persistence.entity.QueryRequestEntity;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.util.ArrayList;

final class QueryRequestSpecifications {

    private QueryRequestSpecifications() {
    }

    static Specification<QueryRequestEntity> forFilter(QueryListFilter filter) {
        return (root, cq, cb) -> {
            cq.orderBy(cb.desc(root.get("createdAt")));
            var predicates = new ArrayList<Predicate>();
            predicates.add(cb.equal(root.get("datasource").get("organization").get("id"),
                    filter.organizationId()));
            if (filter.submittedByUserId() != null) {
                predicates.add(cb.equal(root.get("submittedBy").get("id"),
                        filter.submittedByUserId()));
            }
            if (filter.datasourceId() != null) {
                predicates.add(cb.equal(root.get("datasource").get("id"),
                        filter.datasourceId()));
            }
            if (filter.status() != null) {
                predicates.add(cb.equal(root.get("status"), filter.status()));
            }
            if (filter.queryType() != null) {
                predicates.add(cb.equal(root.get("queryType"), filter.queryType()));
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
