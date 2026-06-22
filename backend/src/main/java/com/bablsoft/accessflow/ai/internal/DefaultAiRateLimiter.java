package com.bablsoft.accessflow.ai.internal;

import com.bablsoft.accessflow.ai.api.AiBudgetExceededException;
import com.bablsoft.accessflow.ai.api.AiRateLimitExceededException;
import com.bablsoft.accessflow.ai.internal.config.AiRateLimitProperties;
import com.bablsoft.accessflow.core.api.AiAnalysisStatsLookupService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * Redis fixed-window request limiter plus a DB-backed monthly token budget (AF-55). Reuses the
 * shared {@link StringRedisTemplate} (the same Redis that backs JWT revocation / ShedLock) for the
 * per-minute counter and the {@link AiAnalysisStatsLookupService} (core.api) for the month-to-date
 * token sum. Both checks are skipped when their configured limit is {@code <= 0}.
 */
@Service
@RequiredArgsConstructor
class DefaultAiRateLimiter implements AiRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(DefaultAiRateLimiter.class);
    private static final String KEY_PREFIX = "accessflow:ai:ratelimit:";
    private static final Duration WINDOW = Duration.ofMinutes(1);

    private final StringRedisTemplate redisTemplate;
    private final AiRateLimitProperties properties;
    private final AiAnalysisStatsLookupService aiAnalysisStatsLookupService;
    private final Clock clock;

    @Override
    public void enforce(UUID organizationId) {
        if (organizationId == null) {
            return;
        }
        enforceRequestsPerMinute(organizationId);
        enforceMonthlyTokenBudget(organizationId);
    }

    private void enforceRequestsPerMinute(UUID organizationId) {
        int limit = properties.requestsPerMinute();
        if (limit <= 0) {
            return;
        }
        long minute = clock.instant().getEpochSecond() / WINDOW.toSeconds();
        var key = KEY_PREFIX + organizationId + ":" + minute;
        Long count = redisTemplate.opsForValue().increment(key);
        if (count != null && count == 1L) {
            redisTemplate.expire(key, WINDOW);
        }
        if (count != null && count > limit) {
            log.warn("AI rate limit exceeded for org {}: {} > {} requests/minute",
                    organizationId, count, limit);
            throw new AiRateLimitExceededException(limit, WINDOW.toSeconds());
        }
    }

    private void enforceMonthlyTokenBudget(UUID organizationId) {
        long budget = properties.tokensPerMonth();
        if (budget <= 0) {
            return;
        }
        Instant monthStart = LocalDate.now(clock)
                .withDayOfMonth(1)
                .atStartOfDay(ZoneOffset.UTC)
                .toInstant();
        long used = aiAnalysisStatsLookupService.sumTokensSince(organizationId, monthStart);
        if (used >= budget) {
            log.warn("AI monthly token budget exhausted for org {}: used {} of {} tokens",
                    organizationId, used, budget);
            throw new AiBudgetExceededException(budget, used);
        }
    }
}
