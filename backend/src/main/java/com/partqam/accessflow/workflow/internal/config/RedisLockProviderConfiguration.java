package com.partqam.accessflow.workflow.internal.config;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.redis.spring.RedisLockProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;

/**
 * Redis-backed {@link LockProvider} for {@link net.javacrumbs.shedlock.spring.annotation.SchedulerLock}.
 *
 * <p>Reuses the auto-configured {@link RedisConnectionFactory} (the same one the JWT
 * {@code RedisRefreshTokenStore} uses). The {@code accessflow:shedlock} environment prefix keeps
 * lock keys disjoint from the {@code refresh:active:} / {@code refresh:user:} keys.
 *
 * <p>This bean is what makes scheduled jobs safe under horizontal scaling: every {@code @Scheduled}
 * method annotated with {@code @SchedulerLock} acquires a Redis lock keyed by name; only one node
 * in the cluster runs the job per invocation.
 */
@Configuration(proxyBeanMethods = false)
class RedisLockProviderConfiguration {

    private static final String ENVIRONMENT = "accessflow:shedlock";

    @Bean
    LockProvider lockProvider(RedisConnectionFactory connectionFactory) {
        return new RedisLockProvider(connectionFactory, ENVIRONMENT);
    }
}
