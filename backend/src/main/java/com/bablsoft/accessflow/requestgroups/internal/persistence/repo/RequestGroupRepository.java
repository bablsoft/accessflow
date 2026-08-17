package com.bablsoft.accessflow.requestgroups.internal.persistence.repo;

import com.bablsoft.accessflow.requestgroups.internal.persistence.entity.RequestGroupEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RequestGroupRepository extends JpaRepository<RequestGroupEntity, UUID>,
        JpaSpecificationExecutor<RequestGroupEntity> {

    Optional<RequestGroupEntity> findByIdAndOrganizationId(UUID id, UUID organizationId);

    // Native queries with an explicit ::request_group_status cast — the same pattern
    // ApiRequestRepository / QueryRequestRepository use for their scheduled/timeout scans.
    @Query(value = """
            SELECT id FROM request_groups
            WHERE status = 'APPROVED'::request_group_status
              AND scheduled_for IS NOT NULL
              AND scheduled_for <= :now
            """, nativeQuery = true)
    List<UUID> findScheduledDueIds(@Param("now") Instant now);

    @Query(value = """
            SELECT id FROM request_groups
            WHERE status = 'PENDING_REVIEW'::request_group_status
              AND created_at <= :cutoff
            """, nativeQuery = true)
    List<UUID> findStalePendingReviewIds(@Param("cutoff") Instant cutoff);

    /**
     * Bundles past their escalation window (#622). A group has no plan of its own, so the window is
     * the <strong>minimum</strong> non-null {@code escalation_after_hours} across its members'
     * plans — the strictest member decides, matching the weakest-link union
     * {@code GroupReviewPlanResolver} already uses for approvers. A member whose plan has
     * escalation off simply contributes nothing to the MIN rather than disabling it for the bundle.
     */
    @Query(value = """
            SELECT g.id
            FROM request_groups g
            WHERE g.status = 'PENDING_REVIEW'::request_group_status
              AND g.escalated_at IS NULL
              AND g.created_at + (COALESCE((
                    SELECT MIN(rp.escalation_after_hours)
                    FROM request_group_items i
                    LEFT JOIN datasources d ON d.id = i.datasource_id
                    LEFT JOIN api_connectors c ON c.id = i.api_connector_id
                    JOIN review_plans rp
                      ON rp.id = COALESCE(d.review_plan_id, c.review_plan_id)
                    WHERE i.group_id = g.id
                  ), 0) || ' hours')::interval < :now
              AND EXISTS (
                    SELECT 1
                    FROM request_group_items i
                    LEFT JOIN datasources d ON d.id = i.datasource_id
                    LEFT JOIN api_connectors c ON c.id = i.api_connector_id
                    JOIN review_plans rp
                      ON rp.id = COALESCE(d.review_plan_id, c.review_plan_id)
                    WHERE i.group_id = g.id
                      AND rp.escalation_after_hours IS NOT NULL
                  )
            """, nativeQuery = true)
    List<UUID> findEscalationDueIds(@Param("now") Instant now);

    /** Bundles due a reminder, on the minimum non-null nudge cadence across member plans (#622). */
    @Query(value = """
            SELECT g.id
            FROM request_groups g
            WHERE g.status = 'PENDING_REVIEW'::request_group_status
              AND COALESCE(g.last_nudged_at, g.created_at) + (COALESCE((
                    SELECT MIN(rp.nudge_interval_hours)
                    FROM request_group_items i
                    LEFT JOIN datasources d ON d.id = i.datasource_id
                    LEFT JOIN api_connectors c ON c.id = i.api_connector_id
                    JOIN review_plans rp
                      ON rp.id = COALESCE(d.review_plan_id, c.review_plan_id)
                    WHERE i.group_id = g.id
                  ), 0) || ' hours')::interval < :now
              AND EXISTS (
                    SELECT 1
                    FROM request_group_items i
                    LEFT JOIN datasources d ON d.id = i.datasource_id
                    LEFT JOIN api_connectors c ON c.id = i.api_connector_id
                    JOIN review_plans rp
                      ON rp.id = COALESCE(d.review_plan_id, c.review_plan_id)
                    WHERE i.group_id = g.id
                      AND rp.nudge_interval_hours IS NOT NULL
                  )
            """, nativeQuery = true)
    List<UUID> findNudgeDueIds(@Param("now") Instant now);
}
