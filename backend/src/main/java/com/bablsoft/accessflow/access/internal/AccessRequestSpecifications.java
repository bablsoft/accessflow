package com.bablsoft.accessflow.access.internal;

import com.bablsoft.accessflow.access.api.AccessGrantStatus;
import com.bablsoft.accessflow.access.internal.persistence.entity.AccessGrantRequestEntity;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.util.ArrayList;
import java.util.UUID;

/** JPA criteria builders for access-request queries. */
final class AccessRequestSpecifications {

    private AccessRequestSpecifications() {
    }

    /**
     * The caller's own requests, optionally filtered by {@code status} (null = all statuses).
     */
    static Specification<AccessGrantRequestEntity> mine(UUID requesterId, UUID organizationId,
                                                        AccessGrantStatus status) {
        return (root, query, cb) -> {
            var predicates = new ArrayList<Predicate>();
            predicates.add(cb.equal(root.get("requesterId"), requesterId));
            predicates.add(cb.equal(root.get("organizationId"), organizationId));
            if (status != null) {
                predicates.add(cb.equal(root.get("status"), status));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }
}
