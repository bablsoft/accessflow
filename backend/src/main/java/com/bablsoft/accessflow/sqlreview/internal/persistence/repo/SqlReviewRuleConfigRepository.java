package com.bablsoft.accessflow.sqlreview.internal.persistence.repo;

import com.bablsoft.accessflow.sqlreview.internal.persistence.entity.SqlReviewRuleConfigEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface SqlReviewRuleConfigRepository extends JpaRepository<SqlReviewRuleConfigEntity, UUID> {

    List<SqlReviewRuleConfigEntity> findAllByRuleset_IdOrderByRuleIdAsc(UUID rulesetId);

    void deleteAllByRuleset_Id(UUID rulesetId);
}
