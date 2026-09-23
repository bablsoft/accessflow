package com.bablsoft.accessflow.scheduling.internal;

import com.bablsoft.accessflow.scheduling.internal.config.JobMonitoringProperties;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.aopalliance.aop.Advice;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.springframework.aop.Pointcut;
import org.springframework.aop.support.AbstractPointcutAdvisor;
import org.springframework.aop.support.AopUtils;
import org.springframework.aop.support.annotation.AnnotationMatchingPointcut;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Records every run of a {@code @Scheduled} method without touching the job class (#923).
 *
 * <p>Ordered outside ShedLock's advice (which sits at {@link Ordered#LOWEST_PRECEDENCE}) and outside
 * any {@code @Transactional} on the job, so the job's own rollback cannot take the record with it.
 * From out here a lock-skipped tick and a real run look identical, so the row is opened by
 * {@link JobExecutionLockListener} once the lock is held; if no row was opened when the method
 * returns, the tick was skipped and nothing is written.
 *
 * <p>Fail-soft: every recorder failure is logged at WARN and swallowed, and the job's own result or
 * exception passes through untouched.
 *
 * <p>A plain Spring AOP advisor rather than an {@code @Aspect}: AspectJ is only on the classpath
 * transitively, and this is applied by the same auto-proxy creator that applies ShedLock's advisor.
 */
@Slf4j
class ScheduledJobExecutionAdvisor extends AbstractPointcutAdvisor {

    static final int ORDER = Ordered.LOWEST_PRECEDENCE - 100;

    private final transient Pointcut pointcut = new AnnotationMatchingPointcut(null, Scheduled.class, true);
    private final transient Advice advice;

    ScheduledJobExecutionAdvisor(ObjectProvider<JobExecutionRecorder> recorder,
                                 ObjectProvider<JobMonitoringProperties> properties,
                                 ObjectProvider<Environment> environment) {
        this.advice = new RecordingInterceptor(recorder, properties, environment);
        setOrder(ORDER);
    }

    @Override
    public Pointcut getPointcut() {
        return pointcut;
    }

    @Override
    public Advice getAdvice() {
        return advice;
    }

    static final class RecordingInterceptor implements MethodInterceptor {

        private final ObjectProvider<JobExecutionRecorder> recorderProvider;
        private final ObjectProvider<JobMonitoringProperties> propertiesProvider;
        private final ObjectProvider<Environment> environmentProvider;

        RecordingInterceptor(ObjectProvider<JobExecutionRecorder> recorderProvider,
                             ObjectProvider<JobMonitoringProperties> propertiesProvider,
                             ObjectProvider<Environment> environmentProvider) {
            this.recorderProvider = recorderProvider;
            this.propertiesProvider = propertiesProvider;
            this.environmentProvider = environmentProvider;
        }

        @Override
        public Object invoke(MethodInvocation invocation) throws Throwable {
            var properties = propertiesProvider.getIfAvailable();
            var recorder = recorderProvider.getIfAvailable();
            if (properties == null || recorder == null || !properties.isEnabled()) {
                return invocation.proceed();
            }
            var targetClass = invocation.getThis() != null
                    ? AopUtils.getTargetClass(invocation.getThis())
                    : invocation.getMethod().getDeclaringClass();
            var method = AopUtils.getMostSpecificMethod(invocation.getMethod(), targetClass);
            var lock = AnnotatedElementUtils.findMergedAnnotation(method, SchedulerLock.class);
            var jobName = JobNames.of(targetClass, method);
            var context = JobExecutionContext.push(jobName, lock != null ? resolve(lock.name()) : null);
            try {
                if (lock == null) {
                    // No lock to wait for: every call is a real run.
                    open(recorder, context);
                }
                Object result;
                try {
                    result = invocation.proceed();
                } catch (Throwable failure) { // recorded, then rethrown unchanged
                    close(recorder, context, failure);
                    throw failure;
                }
                close(recorder, context, null);
                return result;
            } finally {
                context.pop();
            }
        }

        /** ShedLock resolves {@code ${…}} in lock names before the listener sees them; match that. */
        private String resolve(String lockName) {
            var environment = environmentProvider.getIfAvailable();
            return environment != null ? environment.resolvePlaceholders(lockName) : lockName;
        }

        private static void open(JobExecutionRecorder recorder, JobExecutionContext context) {
            try {
                context.executionId(recorder.open(context.jobName(), context.lockName()));
            } catch (RuntimeException ex) {
                log.warn("Could not record the start of scheduled job {}", context.jobName(), ex);
            }
        }

        private static void close(JobExecutionRecorder recorder, JobExecutionContext context, Throwable failure) {
            if (context.executionId() == null) {
                return;
            }
            try {
                recorder.close(context.executionId(), failure);
            } catch (RuntimeException ex) {
                log.warn("Could not record the end of scheduled job {}", context.jobName(), ex);
            }
        }
    }
}
