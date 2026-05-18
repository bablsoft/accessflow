package com.bablsoft.accessflow.scheduling.internal;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.redis.spring.RedisLockProvider;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisConnectionFactory;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class RedisLockProviderConfigurationTest {

    private final RedisLockProviderConfiguration configuration = new RedisLockProviderConfiguration();

    @Test
    void lockProviderReturnsRedisLockProvider() {
        var connectionFactory = mock(RedisConnectionFactory.class);

        LockProvider provider = configuration.lockProvider(connectionFactory);

        assertThat(provider).isInstanceOf(RedisLockProvider.class);
    }

    @Test
    void lockProviderUsesAccessflowShedlockEnvironmentPrefix() throws Exception {
        var connectionFactory = mock(RedisConnectionFactory.class);

        LockProvider provider = configuration.lockProvider(connectionFactory);

        // RedisLockProvider stores the environment on its internal provider; the environment is
        // what disambiguates AccessFlow lock keys from any other ShedLock tenant on the same Redis.
        Field internalField = RedisLockProvider.class.getDeclaredField("internalRedisLockProvider");
        internalField.setAccessible(true);
        Object internalProvider = internalField.get(provider);
        Field environmentField = internalProvider.getClass().getDeclaredField("environment");
        environmentField.setAccessible(true);
        assertThat(environmentField.get(internalProvider)).isEqualTo("accessflow:shedlock");
    }
}
