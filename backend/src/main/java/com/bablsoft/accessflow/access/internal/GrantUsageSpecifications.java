package com.bablsoft.accessflow.access.internal;

import com.bablsoft.accessflow.access.api.GrantUsageReportQuery;
import com.bablsoft.accessflow.access.internal.persistence.entity.GrantUsageSummaryEntity;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.util.ArrayList;
import java.util.UUID;

/**
 * JPA criteria builders for the over-provisioned access report (#625).
 *
 * <p>Criteria rather than a JPQL query with {@code (:param is null or col = :param)} filters, and
 * that is not a style preference: {@code resource_kind} and {@code recommendation} are PostgreSQL
 * enum columns, and a bound parameter that only ever appears in an {@code IS NULL} test gives the
 * planner nothing to infer a type from — Postgres rejects the statement outright with
 * "could not determine data type of parameter". Omitting the predicate entirely when the filter is
 * absent sidesteps the question, and matches how {@code AccessRequestSpecifications} and the audit /
 * anomaly readers already build optional filters.
 */
final class GrantUsageSpecifications {

    private GrantUsageSpecifications() {
    }

    static Specification<GrantUsageSummaryEntity> report(UUID organizationId,
                                                         GrantUsageReportQuery filter) {
        return (root, query, cb) -> {
            var predicates = new ArrayList<Predicate>();
            predicates.add(cb.equal(root.get("organizationId"), organizationId));
            if (filter.resourceKind() != null) {
                predicates.add(cb.equal(root.get("resourceKind"), filter.resourceKind()));
            }
            if (!filter.recommendations().isEmpty()) {
                predicates.add(root.get("recommendation").in(filter.recommendations()));
            }
            if (filter.resourceId() != null) {
                predicates.add(cb.equal(root.get("resourceId"), filter.resourceId()));
            }
            if (filter.userId() != null) {
                predicates.add(cb.equal(root.get("userId"), filter.userId()));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }
}
