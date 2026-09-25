package com.bablsoft.accessflow.core.internal.persistence.repo;

import com.bablsoft.accessflow.core.internal.persistence.entity.DataBudgetUsageEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.UUID;

public interface DataBudgetUsageRepository extends JpaRepository<DataBudgetUsageEntity, UUID> {

    /** Trailing-window totals for one user on one datasource; zeros when nothing was read. */
    @Query("""
            select coalesce(sum(u.rowsRead), 0) as rowsRead,
                   coalesce(sum(u.bytesRead), 0) as bytesRead
            from DataBudgetUsageEntity u
            where u.userId = :userId
              and u.datasourceId = :datasourceId
              and u.occurredAt >= :since
            """)
    UsageTotals sumSince(@Param("userId") UUID userId,
                         @Param("datasourceId") UUID datasourceId,
                         @Param("since") Instant since);

    /**
     * Serializes charges for one (user, datasource) until the transaction ends, so the before-read
     * and the insert of one charge are never interleaved with another's.
     */
    @Query(value = "SELECT 1 FROM (SELECT pg_advisory_xact_lock(:key)) AS l", nativeQuery = true)
    Integer lockUserDatasource(@Param("key") long key);

    @Modifying
    @Query("delete from DataBudgetUsageEntity u where u.occurredAt < :cutoff")
    int deleteOlderThan(@Param("cutoff") Instant cutoff);

    interface UsageTotals {
        Long getRowsRead();

        Long getBytesRead();
    }
}
