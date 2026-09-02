package com.bablsoft.accessflow.deploygov.internal;

import com.bablsoft.accessflow.deploygov.internal.persistence.entity.DeploymentEnvironmentEntity;
import com.bablsoft.accessflow.deploygov.internal.persistence.entity.DeploymentEnvironmentVersionEntity;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Subquery;
import org.springframework.data.jpa.domain.Specification;

import java.util.ArrayList;
import java.util.UUID;

final class DeploymentEnvironmentVersionSpecifications {

    private DeploymentEnvironmentVersionSpecifications() {
    }

    /**
     * Org-scoped listing with optional pipeline and tag filters, most recently updated first.
     * The tag filter is a correlated {@code EXISTS} against {@code deployment_environments} —
     * deploygov entities carry no JPA associations, so the environment row is matched by id.
     * {@code array_position} returns the 1-based index of the value in the array, so {@code > 0}
     * means "tag is in the array". Deliberately not the V51 {@code IS NOT NULL} shape: Hibernate
     * recognises {@code array_position} and renders it null-safely as
     * {@code coalesce(array_position(...), 0)}, which is never null — {@code IS NOT NULL} would
     * match every row, while {@code > 0} is correct under both renderings.
     */
    static Specification<DeploymentEnvironmentVersionEntity> forList(UUID organizationId,
                                                                     UUID pipelineId, String tag) {
        return (root, cq, cb) -> {
            cq.orderBy(cb.desc(root.get("updatedAt")));
            var predicates = new ArrayList<Predicate>();
            predicates.add(cb.equal(root.get("organizationId"), organizationId));
            if (pipelineId != null) {
                predicates.add(cb.equal(root.get("pipelineId"), pipelineId));
            }
            if (tag != null && !tag.isBlank()) {
                Subquery<UUID> sub = cq.subquery(UUID.class);
                var env = sub.from(DeploymentEnvironmentEntity.class);
                Expression<Integer> position = cb.function(
                        "array_position", Integer.class,
                        env.get("tags"), cb.literal(tag.trim()));
                sub.select(env.get("id")).where(
                        cb.equal(env.get("id"), root.get("environmentId")),
                        cb.gt(position, 0));
                predicates.add(cb.exists(sub));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}
