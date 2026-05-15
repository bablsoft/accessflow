package com.bablsoft.accessflow.security.internal.saml;

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
class SamlExchangeCodeStoreTest {

    @Mock StringRedisTemplate redisTemplate;
    @Mock ValueOperations<String, String> ops;

    private SamlExchangeCodeStore store;

    @BeforeEach
    void setUp() {
        var props = new SamlRedirectProperties("http://frontend/auth/saml/callback", Duration.ofMinutes(1));
        store = new SamlExchangeCodeStore(redisTemplate, props);
    }

    @Test
    void issueWritesToRedisWithConfiguredTtl() {
        when(redisTemplate.opsForValue()).thenReturn(ops);
        var userId = UUID.randomUUID();

        var code = store.issue(userId);

        assertThat(code).isNotBlank();
        verify(ops).set(startsWith("saml:exchange:"), eq(userId.toString()), any(Duration.class));
    }

    @Test
    void issueFallsBackToOneMinuteWhenTtlIsNull() {
        when(redisTemplate.opsForValue()).thenReturn(ops);
        var props = new SamlRedirectProperties("http://frontend/auth/saml/callback", null);
        var store = new SamlExchangeCodeStore(redisTemplate, props);

        var code = store.issue(UUID.randomUUID());

        assertThat(code).isNotBlank();
        verify(ops).set(startsWith("saml:exchange:"), anyString(), eq(Duration.ofMinutes(1)));
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
