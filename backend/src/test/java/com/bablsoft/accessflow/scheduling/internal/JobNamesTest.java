package com.bablsoft.accessflow.scheduling.internal;

import com.bablsoft.accessflow.scheduling.internal.scheduled.JobExecutionRetentionJob;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;

import static org.assertj.core.api.Assertions.assertThat;

class JobNamesTest {

    @Test
    void singleMethodJobIsNamedAfterItsClass() throws Exception {
        var method = JobExecutionRetentionJob.class.getMethod("run");
        assertThat(JobNames.of(JobExecutionRetentionJob.class, method)).isEqualTo("JobExecutionRetentionJob");
    }

    @Test
    void classWithSeveralScheduledMethodsIsQualifiedPerMethod() throws Exception {
        assertThat(JobNames.of(TwoJobs.class, TwoJobs.class.getDeclaredMethod("hourly")))
                .isEqualTo("TwoJobs#hourly");
        assertThat(JobNames.of(TwoJobs.class, TwoJobs.class.getDeclaredMethod("daily")))
                .isEqualTo("TwoJobs#daily");
    }

    @Test
    void moduleIsTheFirstPackageSegmentUnderTheRoot() {
        assertThat(JobNames.moduleOf(JobExecutionRetentionJob.class)).isEqualTo("scheduling");
        assertThat(JobNames.moduleOf(String.class)).isNull();
    }

    static class TwoJobs {
        @Scheduled(cron = "@hourly")
        void hourly() {
            // sample
        }

        @Scheduled(cron = "@daily")
        void daily() {
            // sample
        }
    }
}
