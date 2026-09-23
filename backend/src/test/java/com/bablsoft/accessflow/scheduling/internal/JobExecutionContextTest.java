package com.bablsoft.accessflow.scheduling.internal;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JobExecutionContextTest {

    @Test
    void nestedPushRestoresTheOuterContextOnPop() {
        var outer = JobExecutionContext.push("Outer", "outerLock");
        var inner = JobExecutionContext.push("Inner", "innerLock");
        assertThat(JobExecutionContext.current()).isSameAs(inner);
        assertThat(inner.jobName()).isEqualTo("Inner");
        assertThat(inner.lockName()).isEqualTo("innerLock");

        inner.pop();
        assertThat(JobExecutionContext.current()).isSameAs(outer);

        outer.pop();
        assertThat(JobExecutionContext.current()).isNull();
    }
}
