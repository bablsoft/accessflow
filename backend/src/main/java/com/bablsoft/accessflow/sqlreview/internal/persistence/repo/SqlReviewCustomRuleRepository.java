package com.bablsoft.accessflow.sqlreview.internal.persistence.repo;

import com.bablsoft.accessflow.sqlreview.internal.persistence.entity.SqlReviewCustomRuleEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface SqlReviewCustomRuleRepository extends JpaRepository<SqlReviewCustomRuleEntity, UUID> {

    List<SqlReviewCustomRuleEntity> findAllByOrganizationIdAndEnabledTrueOrderByRuleIdAsc(UUID organizationId);

    boolean existsByOrganizationIdAndRuleId(UUID organizationId, String ruleId);
}
