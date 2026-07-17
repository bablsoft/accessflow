package com.bablsoft.accessflow.notifications.internal;

import com.bablsoft.accessflow.notifications.internal.config.TicketingProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TicketingReplayGuardTest {

    @Mock StringRedisTemplate redisTemplate;
    @Mock ValueOperations<String, String> ops;

    private TicketingReplayGuard guard;

    @BeforeEach
    void setUp() {
        guard = new TicketingReplayGuard(redisTemplate,
                new TicketingProperties(Duration.ofMinutes(5)));
    }

    @Test
    void firstSeenReturnsTrueWhenKeyNewlySet() {
        when(redisTemplate.opsForValue()).thenReturn(ops);
        when(ops.setIfAbsent(startsWith("ticketing:sig:"), eq("1"), any(Duration.class)))
                .thenReturn(true);

        assertThat(guard.firstSeen("sha256=abc")).isTrue();
        verify(ops).setIfAbsent(eq("ticketing:sig:sha256=abc"), eq("1"),
                eq(Duration.ofMinutes(5)));
    }

    @Test
    void firstSeenReturnsFalseOnReplay() {
        when(redisTemplate.opsForValue()).thenReturn(ops);
        when(ops.setIfAbsent(startsWith("ticketing:sig:"), eq("1"), any(Duration.class)))
                .thenReturn(false);

        assertThat(guard.firstSeen("sha256=abc")).isFalse();
    }

    @Test
    void firstSeenReturnsFalseForNullSetIfAbsent() {
        when(redisTemplate.opsForValue()).thenReturn(ops);
        when(ops.setIfAbsent(any(), any(), any(Duration.class))).thenReturn(null);

        assertThat(guard.firstSeen("sha256=abc")).isFalse();
    }

    @Test
    void firstSeenRejectsBlankSignatureWithoutTouchingRedis() {
        assertThat(guard.firstSeen(null)).isFalse();
        assertThat(guard.firstSeen("   ")).isFalse();
        verifyNoInteractions(redisTemplate);
    }
}
