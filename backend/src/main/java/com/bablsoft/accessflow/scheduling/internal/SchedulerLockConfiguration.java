package com.bablsoft.accessflow.scheduling.internal;

import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.context.annotation.Configuration;

/**
 * Enables ShedLock's {@code @SchedulerLock} AOP advice for the whole application.
 *
 * <p>Deliberately separate from {@link SchedulingConfiguration}, which can be switched off in
 * tests: the lock advice must stay wired regardless, so the ShedLock bean graph is still built and
 * asserted when scheduling is disabled.
 *
 * <p>The default {@code lockAtMostFor} of {@code PT10M} is a safety net for jobs that omit an
 * explicit value; individual jobs should still set per-method {@code lockAtMostFor} and
 * {@code lockAtLeastFor} on their {@code @SchedulerLock} annotation.
 */
@Configuration(proxyBeanMethods = false)
@EnableSchedulerLock(defaultLockAtMostFor = "PT10M")
class SchedulerLockConfiguration {
}
