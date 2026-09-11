package com.bablsoft.accessflow.sqlreview.internal.persistence.repo;

import com.bablsoft.accessflow.core.api.DatasourceEnvironment;
import com.bablsoft.accessflow.sqlreview.internal.persistence.entity.SqlReviewRulesetEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SqlReviewRulesetRepository extends JpaRepository<SqlReviewRulesetEntity, UUID> {

    List<SqlReviewRulesetEntity> findAllByOrganizationIdOrderByNameAsc(UUID organizationId);

    Optional<SqlReviewRulesetEntity> findByIdAndOrganizationId(UUID id, UUID organizationId);

    Optional<SqlReviewRulesetEntity> findByOrganizationIdAndEnvironment(UUID organizationId,
                                                                        DatasourceEnvironment environment);

    /** The organization-wide default ruleset — the one row with a null environment. */
    Optional<SqlReviewRulesetEntity> findByOrganizationIdAndEnvironmentIsNull(UUID organizationId);
}
