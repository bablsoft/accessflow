package com.bablsoft.accessflow.scheduling.internal;

import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Cross-cutting scheduler infrastructure for the application.
 *
 * <p>Activates Spring's scheduling support and ShedLock's {@code @SchedulerLock} AOP advice once
 * for the whole application. Any module that declares a {@code @Scheduled} method picks this up
 * implicitly — no module needs to depend on another module's internals to enable scheduling.
 *
 * <p>The default {@code lockAtMostFor} of {@code PT10M} is a safety net for jobs that omit an
 * explicit value; individual jobs should still set per-method {@code lockAtMostFor} and
 * {@code lockAtLeastFor} on their {@code @SchedulerLock} annotation.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableSchedulerLock(defaultLockAtMostFor = "PT10M")
class SchedulingConfiguration {
}
