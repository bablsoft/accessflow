package com.bablsoft.accessflow.audit.internal;

import com.bablsoft.accessflow.audit.api.AuditLogQuery;
import com.bablsoft.accessflow.audit.internal.persistence.entity.AuditLogEntity;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.time.Instant;
import java.util.ArrayList;
import java.util.UUID;

/** Builds JPA {@link Specification}s for the audit log read endpoint. */
final class AuditLogSpecifications {

    private AuditLogSpecifications() {
    }

    static Specification<AuditLogEntity> forVerification(UUID organizationId, Instant from, Instant to) {
        return (root, cq, cb) -> {
            var predicates = new ArrayList<Predicate>();
            predicates.add(cb.equal(root.get("organizationId"), organizationId));
            if (from != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), from));
            }
            if (to != null) {
                predicates.add(cb.lessThan(root.get("createdAt"), to));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }

    static Specification<AuditLogEntity> forQuery(UUID organizationId, AuditLogQuery query) {
        return (root, cq, cb) -> {
            cq.orderBy(cb.desc(root.get("createdAt")));
            var predicates = new ArrayList<Predicate>();
            predicates.add(cb.equal(root.get("organizationId"), organizationId));
            if (query.actorId() != null) {
                predicates.add(cb.equal(root.get("actorId"), query.actorId()));
            }
            if (query.action() != null) {
                predicates.add(cb.equal(root.get("action"), query.action().name()));
            }
            if (query.resourceType() != null) {
                predicates.add(cb.equal(root.get("resourceType"), query.resourceType().dbValue()));
            }
            if (query.resourceId() != null) {
                predicates.add(cb.equal(root.get("resourceId"), query.resourceId()));
            }
            if (query.from() != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), query.from()));
            }
            if (query.to() != null) {
                predicates.add(cb.lessThan(root.get("createdAt"), query.to()));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}
