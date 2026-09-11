package com.bablsoft.accessflow.sqlreview.internal.persistence.repo;

import com.bablsoft.accessflow.sqlreview.internal.persistence.entity.SqlReviewRuleConfigEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface SqlReviewRuleConfigRepository extends JpaRepository<SqlReviewRuleConfigEntity, UUID> {

    List<SqlReviewRuleConfigEntity> findAllByRuleset_IdOrderByRuleIdAsc(UUID rulesetId);

    /** Bulk delete for a full rule replacement; the caller supplies the transaction. */
    @Modifying
    @Query("delete from SqlReviewRuleConfigEntity c where c.ruleset.id = :rulesetId")
    void deleteAllByRulesetId(@Param("rulesetId") UUID rulesetId);
}
