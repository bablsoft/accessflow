package com.bablsoft.accessflow.serviceaccounts.internal;

import com.bablsoft.accessflow.serviceaccounts.api.ServiceAccountRateLimitExceededException;
import com.bablsoft.accessflow.serviceaccounts.internal.config.ServiceAccountRateLimitProperties;
import com.bablsoft.accessflow.serviceaccounts.internal.persistence.repo.ServiceAccountRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;

import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Redis fixed-window limiter keyed by the calling identity (#873), mirroring
 * {@code ai.internal.DefaultAiRateLimiter}'s increment-then-expire idiom on the shared
 * {@link StringRedisTemplate}. Limits come from the {@code service_accounts} row when set and from
 * {@link ServiceAccountRateLimitProperties} otherwise; a value {@code <= 0} disables that window.
 *
 * <p><b>Fails OPEN — deliberately.</b> CLAUDE.md's fail-closed rules govern <i>authorization</i>
 * (freeze windows, the deployment gate, row security). This is a resource guardrail: the request is
 * still fully authenticated, permission-bounded and audited. Failing closed on a Redis blip would
 * halt every agent and every CI pipeline at once — a self-inflicted outage strictly worse than
 * briefly unmetered traffic. This diverges from {@code DefaultAiRateLimiter}, which propagates the
 * error because there a failure merely degrades one analysis to human review. Do not "fix" it.
 */
@Service
@RequiredArgsConstructor
class DefaultServiceAccountRateLimiter implements ServiceAccountRateLimiter {

    static final String KEY_PREFIX = "accessflow:serviceaccounts:ratelimit:";
    static final Duration MINUTE_WINDOW = Duration.ofMinutes(1);
    static final Duration DAY_WINDOW = Duration.ofDays(1);
    static final String WINDOW_MINUTE = "minute";
    static final String WINDOW_DAY = "day";

    private static final Logger log = LoggerFactory.getLogger(DefaultServiceAccountRateLimiter.class);
    private static final long WARN_THROTTLE_SECONDS = 60;

    private final StringRedisTemplate redisTemplate;
    private final ServiceAccountRateLimitProperties properties;
    private final ServiceAccountRepository repository;
    private final Clock clock;

    private final AtomicLong lastOutageWarnEpochSecond = new AtomicLong();
    private final AtomicLong lastExceededWarnEpochSecond = new AtomicLong();

    @Override
    public void enforce(UUID userId) {
        if (userId == null) {
            return;
        }
        try {
            var limits = resolve(userId);
            if (limits.perMinute() <= 0 && limits.perDay() <= 0) {
                return;
            }
            long epochSecond = clock.instant().getEpochSecond();
            if (limits.perMinute() > 0) {
                enforceWindow(userId, KEY_PREFIX + userId + ":" + epochSecond / MINUTE_WINDOW.toSeconds(),
                        MINUTE_WINDOW, limits.perMinute(), epochSecond, WINDOW_MINUTE);
            }
            if (limits.perDay() > 0) {
                enforceWindow(userId, KEY_PREFIX + userId + ":d:" + epochSecond / DAY_WINDOW.toSeconds(),
                        DAY_WINDOW, limits.perDay(), epochSecond, WINDOW_DAY);
            }
        } catch (DataAccessException | TransactionException ex) {
            // TransactionException covers CannotCreateTransactionException — what a Postgres outage
            // or an exhausted pool surfaces as from the repository's read-only transaction.
            warnOutageThrottled(userId, ex);
        }
    }

    private Limits resolve(UUID userId) {
        return repository.findById(userId)
                .map(account -> new Limits(
                        account.getRateLimitPerMinute() != null
                                ? account.getRateLimitPerMinute() : properties.requestsPerMinute(),
                        account.getRateLimitPerDay() != null
                                ? account.getRateLimitPerDay() : properties.requestsPerDay()))
                .orElseGet(() -> new Limits(properties.requestsPerMinute(), properties.requestsPerDay()));
    }

    private void enforceWindow(UUID userId, String key, Duration window, int limit, long epochSecond,
                               String windowName) {
        Long count = redisTemplate.opsForValue().increment(key);
        if (count != null && count == 1L) {
            redisTemplate.expire(key, window);
        }
        if (count != null && count > limit) {
            long remaining = Math.max(1, window.toSeconds() - epochSecond % window.toSeconds());
            // Throttled: the runaway client this exists for would otherwise produce one WARN per
            // rejected request.
            if (throttle(lastExceededWarnEpochSecond, epochSecond)) {
                log.warn("API-key rate limit exceeded for user {}: {} > {} requests/{}",
                        userId, count, limit, windowName);
            } else {
                log.debug("API-key rate limit exceeded for user {}: {} > {} requests/{}",
                        userId, count, limit, windowName);
            }
            throw new ServiceAccountRateLimitExceededException(limit, remaining, windowName);
        }
    }

    private void warnOutageThrottled(UUID userId, RuntimeException ex) {
        if (throttle(lastOutageWarnEpochSecond, clock.instant().getEpochSecond())) {
            log.warn("API-key rate limiter unavailable — allowing request for user {} unmetered "
                    + "(fail-open): {}", userId, ex.getMessage());
        } else {
            log.debug("API-key rate limiter unavailable — allowing request for user {} unmetered", userId);
        }
    }

    /** True at most once per {@link #WARN_THROTTLE_SECONDS}; lock-free, safe under concurrent requests. */
    private static boolean throttle(AtomicLong lastEpochSecond, long now) {
        long last = lastEpochSecond.get();
        return now - last >= WARN_THROTTLE_SECONDS && lastEpochSecond.compareAndSet(last, now);
    }

    private record Limits(int perMinute, int perDay) {
    }
}
