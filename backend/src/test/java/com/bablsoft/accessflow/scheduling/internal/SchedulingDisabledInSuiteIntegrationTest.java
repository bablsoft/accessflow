package com.bablsoft.accessflow.scheduling.internal;

import com.bablsoft.accessflow.TestcontainersConfig;
import net.javacrumbs.shedlock.core.LockProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins {@code accessflow.scheduling.enabled=false} in a real context.
 *
 * <p>{@link SchedulingConfigurationTest} asserts the switch over an {@code ApplicationContextRunner},
 * which never reads {@code src/test/resources/application.properties} — so deleting that line would
 * re-arm every {@code @Scheduled} job against the shared test database with nothing turning red,
 * and the symptom would be diffuse cross-class flakes rather than a failure naming the cause
 * (#765). This is the assertion that fails instead.
 */
@SpringBootTest
@ImportTestcontainers(TestcontainersConfig.class)
class SchedulingDisabledInSuiteIntegrationTest {

    @Autowired ApplicationContext context;

    @Test
    @DisplayName("no @Scheduled job is scheduled in the test suite")
    void schedulingIsDisabledForTheSuite() {
        // Assert on the scheduled tasks, NOT on the presence of a
        // ScheduledAnnotationBeanPostProcessor bean: Spring's own
        // org.springframework.scheduling.annotation.SchedulingConfiguration registers that
        // processor via another @EnableScheduling on the classpath, so it is present either way.
        assertThat(context.getBean(ScheduledAnnotationBeanPostProcessor.class).getScheduledTasks())
                .describedAs("accessflow.scheduling.enabled must stay false in "
                        + "backend/src/test/resources/application.properties")
                .isEmpty();

        assertThat(context.getBeanNamesForType(SchedulingConfiguration.class))
                .describedAs("our gated SchedulingConfiguration must not be registered in tests")
                .isEmpty();
    }

    @Test
    @DisplayName("ShedLock stays wired even with scheduling off")
    void schedulerLockRemainsWired() {
        assertThat(context.getBeanNamesForType(LockProvider.class))
                .describedAs("SchedulerLockConfiguration must stay ungated so @SchedulerLock "
                        + "advice is still asserted by the suite")
                .isNotEmpty();
    }
}
