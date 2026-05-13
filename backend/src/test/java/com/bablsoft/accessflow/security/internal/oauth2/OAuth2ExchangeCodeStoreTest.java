package com.bablsoft.accessflow.security.internal.oauth2;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OAuth2ExchangeCodeStoreTest {

    @Mock StringRedisTemplate redisTemplate;
    @Mock ValueOperations<String, String> ops;

    private OAuth2ExchangeCodeStore store;

    @BeforeEach
    void setUp() {
        var props = new OAuth2RedirectProperties("http://frontend/auth/oauth/callback", Duration.ofMinutes(1));
        store = new OAuth2ExchangeCodeStore(redisTemplate, props);
    }

    @Test
    void issueWritesToRedisWithConfiguredTtl() {
        when(redisTemplate.opsForValue()).thenReturn(ops);
        var userId = UUID.randomUUID();

        var code = store.issue(userId);

        assertThat(code).isNotBlank();
        verify(ops).set(startsWith("oauth2:exchange:"), eq(userId.toString()), any(Duration.class));
    }

    @Test
    void consumeReturnsUserIdAndDeletesKey() {
        when(redisTemplate.opsForValue()).thenReturn(ops);
        var userId = UUID.randomUUID();
        when(ops.getAndDelete(anyString())).thenReturn(userId.toString());

        var result = store.consume("ABCDE");

        assertThat(result).contains(userId);
    }

    @Test
    void consumeReturnsEmptyForUnknownCode() {
        when(redisTemplate.opsForValue()).thenReturn(ops);
        when(ops.getAndDelete(anyString())).thenReturn(null);

        assertThat(store.consume("missing")).isEmpty();
    }

    @Test
    void consumeReturnsEmptyForNullOrBlank() {
        assertThat(store.consume(null)).isEmpty();
        assertThat(store.consume("")).isEmpty();
        assertThat(store.consume("   ")).isEmpty();
    }

    @Test
    void consumeReturnsEmptyForCorruptedValue() {
        when(redisTemplate.opsForValue()).thenReturn(ops);
        when(ops.getAndDelete(anyString())).thenReturn("not-a-uuid");

        assertThat(store.consume("corrupt")).isEmpty();
    }
}
