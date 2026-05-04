package com.partqam.accessflow.security.internal.token;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RedisRefreshTokenStoreTest {

    @Mock StringRedisTemplate redisTemplate;
    @Mock ValueOperations<String, String> valueOps;
    @Mock SetOperations<String, String> setOps;

    private RedisRefreshTokenStore store;

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(redisTemplate.opsForSet()).thenReturn(setOps);
        store = new RedisRefreshTokenStore(redisTemplate);
    }

    @Test
    void storeWritesHashedKeyWithTtl() {
        store.store("my-token", "user-123", 3600L);

        verify(valueOps).set(anyString(), eq("user-123"), eq(Duration.ofSeconds(3600)));
    }

    @Test
    void storeKeyContainsHashNotRawToken() {
        store.store("my-token", "user-123", 3600L);

        // The key should not contain the raw token value
        verify(valueOps).set(
                org.mockito.ArgumentMatchers.argThat(key -> !key.contains("my-token")),
                eq("user-123"),
                any(Duration.class));
    }

    @Test
    void isRevokedReturnsFalseWhenKeyExists() {
        when(redisTemplate.hasKey(anyString())).thenReturn(true);

        assertThat(store.isRevoked("my-token")).isFalse();
    }

    @Test
    void isRevokedReturnsTrueWhenKeyAbsent() {
        when(redisTemplate.hasKey(anyString())).thenReturn(false);

        assertThat(store.isRevoked("my-token")).isTrue();
    }

    @Test
    void revokeDeletesActiveKey() {
        when(valueOps.get(anyString())).thenReturn("user-123");

        store.revoke("my-token");

        verify(redisTemplate).delete(anyString());
    }
}
