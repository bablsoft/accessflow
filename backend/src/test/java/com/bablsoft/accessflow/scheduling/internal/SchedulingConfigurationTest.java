package com.bablsoft.accessflow.scheduling.internal;

import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the scheduling on/off switch and, more importantly, the split that makes it safe.
 *
 * <p>The integration suite disables scheduling so its one shared Spring context does not have 36
 * {@code @Scheduled} jobs mutating the shared test database. That must not take ShedLock's advice
 * down with it, or the suite would stop asserting the wiring that production depends on.
 */
class SchedulingConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner();

    @Test
    @DisplayName("scheduling is on when the property is absent")
    void schedulingEnabledByDefault() {
        runner.withUserConfiguration(SchedulingConfiguration.class)
                .run(context -> assertThat(context).hasSingleBean(ScheduledAnnotationBeanPostProcessor.class));
    }

    @Test
    @DisplayName("scheduling is on when the property is explicitly true")
    void schedulingEnabledWhenPropertyTrue() {
        runner.withUserConfiguration(SchedulingConfiguration.class)
                .withPropertyValues("accessflow.scheduling.enabled=true")
                .run(context -> assertThat(context).hasSingleBean(ScheduledAnnotationBeanPostProcessor.class));
    }

    @Test
    @DisplayName("scheduling is off when the property is false")
    void schedulingDisabledWhenPropertyFalse() {
        runner.withUserConfiguration(SchedulingConfiguration.class)
                .withPropertyValues("accessflow.scheduling.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(SchedulingConfiguration.class);
                    assertThat(context).doesNotHaveBean(ScheduledAnnotationBeanPostProcessor.class);
                });
    }

    @Test
    @DisplayName("the scheduling switch is spelled accessflow.scheduling.enabled and defaults to on")
    void conditionalIsDeclaredAsDocumented() {
        var conditional = SchedulingConfiguration.class.getAnnotation(ConditionalOnProperty.class);
        assertThat(conditional).isNotNull();
        assertThat(conditional.name()).containsExactly("accessflow.scheduling.enabled");
        assertThat(conditional.matchIfMissing()).isTrue();
        assertThat(SchedulingConfiguration.class.getAnnotation(EnableScheduling.class)).isNotNull();
    }

    @Test
    @DisplayName("ShedLock advice stays wired when scheduling is disabled")
    void schedulerLockIsUnconditional() {
        assertThat(SchedulerLockConfiguration.class.getAnnotation(ConditionalOnProperty.class))
                .describedAs("SchedulerLockConfiguration must NOT be gated: disabling scheduling in "
                        + "tests would otherwise unwire the @SchedulerLock advice production relies on")
                .isNull();

        var enableSchedulerLock = SchedulerLockConfiguration.class.getAnnotation(EnableSchedulerLock.class);
        assertThat(enableSchedulerLock).isNotNull();
        assertThat(enableSchedulerLock.defaultLockAtMostFor()).isEqualTo("PT10M");

        assertThat(SchedulingConfiguration.class.getAnnotation(EnableSchedulerLock.class))
                .describedAs("@EnableSchedulerLock moved to SchedulerLockConfiguration")
                .isNull();
    }
}
