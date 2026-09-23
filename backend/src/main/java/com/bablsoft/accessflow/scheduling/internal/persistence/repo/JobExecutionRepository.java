package com.bablsoft.accessflow.scheduling.internal.persistence.repo;

import com.bablsoft.accessflow.scheduling.api.JobExecutionStatus;
import com.bablsoft.accessflow.scheduling.internal.persistence.entity.JobExecutionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface JobExecutionRepository extends JpaRepository<JobExecutionEntity, UUID>,
        JpaSpecificationExecutor<JobExecutionEntity> {

    boolean existsByJobName(String jobName);

    /** The newest row of every job (PostgreSQL {@code DISTINCT ON}). */
    @Query(value = """
            select distinct on (job_name) *
            from job_executions
            order by job_name, started_at desc
            """, nativeQuery = true)
    List<JobExecutionEntity> findLatestPerJob();

    /** {@code [jobName, status, count, avgDurationMs]} for rows started at or after {@code since}. */
    @Query("""
            select e.jobName, e.status, count(e), avg(e.durationMs)
            from JobExecutionEntity e
            where e.startedAt >= :since and e.status in :statuses
            group by e.jobName, e.status
            """)
    List<Object[]> summarizeSince(@Param("since") Instant since,
                                  @Param("statuses") List<JobExecutionStatus> statuses);

    /** {@code [jobName, count]} of FAILED rows newer than the job's newest SUCCESS row. */
    @Query(value = """
            select f.job_name, count(*)
            from job_executions f
            where f.status = 'FAILED'
              and f.started_at > coalesce(
                    (select max(s.started_at) from job_executions s
                     where s.job_name = f.job_name and s.status = 'SUCCESS'),
                    '-infinity'::timestamptz)
            group by f.job_name
            """, nativeQuery = true)
    List<Object[]> countConsecutiveFailures();

    @Modifying
    @Transactional
    @Query("delete from JobExecutionEntity e where e.startedAt < :cutoff")
    int deleteStartedBefore(@Param("cutoff") Instant cutoff);

    /** Trims every job to its newest {@code maxPerJob} rows, so one busy job cannot evict another's history. */
    @Modifying
    @Transactional
    @Query(value = """
            delete from job_executions
            where id in (
                select id from (
                    select id, row_number() over (partition by job_name order by started_at desc) as rn
                    from job_executions
                ) ranked
                where ranked.rn > :maxPerJob
            )
            """, nativeQuery = true)
    int trimPerJob(@Param("maxPerJob") int maxPerJob);
}
