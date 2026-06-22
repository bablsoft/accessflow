package com.bablsoft.accessflow.security.internal;

import com.bablsoft.accessflow.security.internal.config.StepUpProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StepUpCodeStoreTest {

    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOps;
    private StepUpCodeStore store;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        store = new StepUpCodeStore(redisTemplate, new StepUpProperties(Duration.ofMinutes(5)));
    }

    @Test
    void issueStoresTokenUnderNamespaceWithTtl() {
        var userId = UUID.randomUUID();

        var token = store.issue(userId);

        assertThat(token).isNotBlank();
        verify(valueOps).set(startsWith("stepup:"), eq(userId.toString()), eq(Duration.ofMinutes(5)));
    }

    @Test
    void consumeReturnsUserIdAndDeletes() {
        var userId = UUID.randomUUID();
        when(valueOps.getAndDelete("stepup:tok")).thenReturn(userId.toString());

        assertThat(store.consume("tok")).contains(userId);
    }

    @Test
    void consumeReturnsEmptyForMissingToken() {
        when(valueOps.getAndDelete("stepup:gone")).thenReturn(null);

        assertThat(store.consume("gone")).isEmpty();
    }

    @Test
    void consumeReturnsEmptyForBlankToken() {
        assertThat(store.consume(" ")).isEmpty();
        assertThat(store.consume(null)).isEmpty();
    }

    @Test
    void consumeReturnsEmptyForCorruptValue() {
        when(valueOps.getAndDelete("stepup:tok")).thenReturn("not-a-uuid");

        assertThat(store.consume("tok")).isEmpty();
    }
}
