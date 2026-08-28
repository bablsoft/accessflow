package com.bablsoft.accessflow.scheduling.internal;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Cross-cutting scheduler infrastructure for the application.
 *
 * <p>Activates Spring's scheduling support once for the whole application. Any module that declares
 * a {@code @Scheduled} method picks this up implicitly — no module needs to depend on another
 * module's internals to enable scheduling.
 *
 * <p>Gated on {@code accessflow.scheduling.enabled} (default {@code true}, so production and the
 * demo stack are unaffected). The integration suite sets it to {@code false}: once the Spring
 * test-context cache works, ~90 test classes share a single context that is never idle, so its 36
 * {@code @Scheduled} jobs would run continuously against the one shared Postgres and mutate rows
 * other tests are asserting on. No job declares an {@code initialDelay}, so pinning cadences would
 * not suppress the burst at context start. Tests that exercise a job re-enable this inline.
 *
 * <p>ShedLock's advice lives in {@link SchedulerLockConfiguration} so that it stays wired — and
 * asserted — even when scheduling itself is off.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "accessflow.scheduling.enabled", matchIfMissing = true)
@EnableScheduling
class SchedulingConfiguration {
}
