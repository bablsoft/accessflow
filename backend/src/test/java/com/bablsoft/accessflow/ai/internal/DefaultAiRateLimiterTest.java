package com.bablsoft.accessflow.ai.internal;

import com.bablsoft.accessflow.ai.api.AiBudgetExceededException;
import com.bablsoft.accessflow.ai.api.AiRateLimitExceededException;
import com.bablsoft.accessflow.ai.internal.config.AiRateLimitProperties;
import com.bablsoft.accessflow.core.api.AiAnalysisStatsLookupService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultAiRateLimiterTest {

    @Mock StringRedisTemplate redisTemplate;
    @Mock ValueOperations<String, String> valueOps;
    @Mock AiAnalysisStatsLookupService statsLookupService;

    // Fixed mid-month so the budget window resolves to 2026-06-01T00:00:00Z.
    private final Clock clock = Clock.fixed(Instant.parse("2026-06-15T12:00:00Z"), ZoneOffset.UTC);
    private final Instant monthStart = Instant.parse("2026-06-01T00:00:00Z");
    private final UUID orgId = UUID.randomUUID();

    private DefaultAiRateLimiter limiter(int requestsPerMinute, long tokensPerMonth) {
        return new DefaultAiRateLimiter(redisTemplate,
                new AiRateLimitProperties(requestsPerMinute, tokensPerMonth),
                statsLookupService, clock);
    }

    @Test
    void nullOrganizationIsNoOp() {
        limiter(30, 1000).enforce(null);
        verifyNoInteractions(redisTemplate, statsLookupService);
    }

    @Test
    void bothLimitsDisabledIsNoOp() {
        limiter(0, 0).enforce(orgId);
        verifyNoInteractions(redisTemplate, statsLookupService);
    }

    @Test
    void firstRequestInWindowSetsExpiryAndPasses() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.increment(anyString())).thenReturn(1L);

        limiter(30, 0).enforce(orgId);

        verify(redisTemplate).expire(anyString(), eq(Duration.ofMinutes(1)));
        verifyNoInteractions(statsLookupService);
    }

    @Test
    void requestAtLimitPasses() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.increment(anyString())).thenReturn(30L);

        limiter(30, 0).enforce(orgId);

        verify(redisTemplate, never()).expire(anyString(), any(Duration.class));
    }

    @Test
    void requestOverLimitThrows() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.increment(anyString())).thenReturn(31L);

        assertThatThrownBy(() -> limiter(30, 0).enforce(orgId))
                .isInstanceOfSatisfying(AiRateLimitExceededException.class, ex -> {
                    assertThat(ex.limit()).isEqualTo(30);
                    assertThat(ex.retryAfterSeconds()).isEqualTo(60L);
                });
    }

    @Test
    void budgetUnderLimitPasses() {
        when(statsLookupService.sumTokensSince(orgId, monthStart)).thenReturn(500L);

        limiter(0, 1000).enforce(orgId);

        verify(statsLookupService).sumTokensSince(orgId, monthStart);
        verifyNoInteractions(redisTemplate);
    }

    @Test
    void budgetReachedThrows() {
        when(statsLookupService.sumTokensSince(orgId, monthStart)).thenReturn(1000L);

        assertThatThrownBy(() -> limiter(0, 1000).enforce(orgId))
                .isInstanceOfSatisfying(AiBudgetExceededException.class, ex -> {
                    assertThat(ex.budget()).isEqualTo(1000L);
                    assertThat(ex.used()).isEqualTo(1000L);
                });
    }
}
