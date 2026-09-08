package com.bablsoft.accessflow.ai.internal.help;

import com.bablsoft.accessflow.ai.api.HelpChatRateLimitExceededException;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.util.UUID;

/**
 * A per-user fixed-window limit on help questions, on top of the organization-wide AI limit (AF-903).
 *
 * <p>Without it the org-wide limiter is the only thing in the way, and one user holding Enter drains
 * the whole organization's 30 requests a minute — for everyone, including the SQL analyzer that
 * queries actually depend on. The limit itself is per organization
 * ({@code help_agent_config.per_user_requests_per_minute}, default 6) because how chatty a help panel
 * should be is a tenant's call, not a deployment's.
 *
 * <p>Same Redis counter idiom as {@code DefaultAiRateLimiter}, including the {@code count == 1} TTL:
 * setting the expiry only on the first increment is what makes the window fixed rather than sliding
 * forward on every request, and what stops the key living forever if the process dies mid-window.
 * The counter is incremented <em>before</em> the work, so a turn that then fails still counts —
 * otherwise a client retrying on error would never be limited at all.
 */
@Component
@RequiredArgsConstructor
public class HelpChatRateLimiter {

    static final String KEY_PREFIX = "accessflow:ai:help:ratelimit:";

    private static final Logger log = LoggerFactory.getLogger(HelpChatRateLimiter.class);
    private static final Duration WINDOW = Duration.ofMinutes(1);

    private final StringRedisTemplate redisTemplate;
    private final Clock clock;

    /**
     * @param limit the organization's per-user cap; {@code <= 0} disables the check, matching how
     *              every other AccessFlow rate limit reads a non-positive limit
     */
    public void enforce(UUID organizationId, UUID userId, int limit) {
        if (organizationId == null || userId == null || limit <= 0) {
            return;
        }
        long minute = clock.instant().getEpochSecond() / WINDOW.toSeconds();
        var key = KEY_PREFIX + organizationId + ":" + userId + ":" + minute;
        Long count = redisTemplate.opsForValue().increment(key);
        if (count != null && count == 1L) {
            redisTemplate.expire(key, WINDOW);
        }
        if (count != null && count > limit) {
            log.warn("Help chat rate limit exceeded for user {} in org {}: {} > {} requests/minute",
                    userId, organizationId, count, limit);
            throw new HelpChatRateLimitExceededException(limit, WINDOW.toSeconds());
        }
    }
}
