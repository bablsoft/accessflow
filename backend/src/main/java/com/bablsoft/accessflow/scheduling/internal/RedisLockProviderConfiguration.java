package com.bablsoft.accessflow.scheduling.internal;

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
 *
 * <p>{@code safeUpdate(true)} is deliberate (AF-660): the plain constructor releases a lock with an
 * unconditional {@code DEL}, so a holder that overruns its {@code lockAtMostFor} — after Redis has
 * expired the key and a second node has taken it — deletes <em>that</em> node's lock on its way
 * out, admitting a third. The safe form compares the lock's own value first, at the cost of a Lua
 * {@code EVAL} (single key, so cluster-safe) instead of a bare {@code DEL}.
 */
@Configuration(proxyBeanMethods = false)
class RedisLockProviderConfiguration {

    private static final String ENVIRONMENT = "accessflow:shedlock";

    @Bean
    LockProvider lockProvider(RedisConnectionFactory connectionFactory) {
        return new RedisLockProvider.Builder(connectionFactory)
                .environment(ENVIRONMENT)
                .safeUpdate(true)
                .build();
    }
}
