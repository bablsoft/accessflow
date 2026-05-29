package com.bablsoft.accessflow.notifications.internal;

import com.bablsoft.accessflow.notifications.internal.config.SlackProperties;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SlackLinkCodeStoreTest {

    @Mock StringRedisTemplate redisTemplate;
    @Mock ValueOperations<String, String> ops;

    private SlackLinkCodeStore store;

    @BeforeEach
    void setUp() {
        store = new SlackLinkCodeStore(redisTemplate, new SlackProperties(Duration.ofMinutes(10), null));
    }

    @Test
    void issueWritesCodeWithTtlAndReturnsExpiry() {
        when(redisTemplate.opsForValue()).thenReturn(ops);
        var userId = UUID.randomUUID();

        var issued = store.issue(userId);

        assertThat(issued.code()).isNotBlank();
        assertThat(issued.expiresAt()).isNotNull();
        verify(ops).set(startsWith("slack:link:"), eq(userId.toString()), eq(Duration.ofMinutes(10)));
    }

    @Test
    void consumeReturnsUserIdAndDeletes() {
        when(redisTemplate.opsForValue()).thenReturn(ops);
        var userId = UUID.randomUUID();
        when(ops.getAndDelete(anyString())).thenReturn(userId.toString());

        assertThat(store.consume("ABC")).contains(userId);
        verify(ops).getAndDelete("slack:link:ABC");
    }

    @Test
    void consumeTrimsCode() {
        when(redisTemplate.opsForValue()).thenReturn(ops);
        var userId = UUID.randomUUID();
        when(ops.getAndDelete("slack:link:ABC")).thenReturn(userId.toString());

        assertThat(store.consume("  ABC  ")).contains(userId);
    }

    @Test
    void consumeReturnsEmptyForUnknownCode() {
        when(redisTemplate.opsForValue()).thenReturn(ops);
        when(ops.getAndDelete(anyString())).thenReturn(null);

        assertThat(store.consume("missing")).isEmpty();
    }

    @Test
    void consumeReturnsEmptyForBlankOrCorrupt() {
        assertThat(store.consume(null)).isEmpty();
        assertThat(store.consume("  ")).isEmpty();

        when(redisTemplate.opsForValue()).thenReturn(ops);
        when(ops.getAndDelete(anyString())).thenReturn("not-a-uuid");
        assertThat(store.consume("corrupt")).isEmpty();
    }
}
