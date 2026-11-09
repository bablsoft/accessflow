package com.bablsoft.accessflow.apigov.internal;

import com.bablsoft.accessflow.apigov.api.ApiConnectorLimitKind;
import com.bablsoft.accessflow.apigov.internal.persistence.entity.ApiConnectorEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Connector rate limits enforced at execution (#972): a per-minute token bucket (burst =
 * {@code calls_per_minute}, refilled at {@code calls_per_minute / 60} tokens per second) and a UTC
 * fixed-window daily counter, plus — when {@code per_user_share_percent} is set — the same pair per
 * {@code (connector, user)} sized {@code ceil(limit × share / 100)}. Every configured key is checked
 * and consumed by one atomic Lua script, so a call refused by any limit consumes nothing.
 *
 * <p><strong>Fails open.</strong> A Redis failure ({@link DataAccessException}) is logged at WARN
 * (throttled) and the call is allowed. This deliberately diverges from {@code DefaultAiRateLimiter},
 * which propagates the failure: a connector rate limit protects the upstream from load, it is not an
 * authorization control, and failing closed would halt every governed API call on a Redis blip. Do
 * not "fix" it.
 */
@Service
class ApiConnectorRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(ApiConnectorRateLimiter.class);
    static final String KEY_PREFIX = "accessflow:apigov:ratelimit:";
    private static final long OUTAGE_WARN_INTERVAL_SECONDS = 60;
    private static final long BUCKET_TTL_MS = Duration.ofMinutes(2).toMillis();
    private static final long DAY_TTL_SLACK_SECONDS = 60;

    // KEYS[i] is either a token bucket ('b') or a daily counter ('d'); ARGV[1] = now in epoch ms,
    // then three ARGV per key: kind, limit, param (bucket TTL ms | seconds until the next UTC day).
    // Phase 1 evaluates every key without writing; phase 2 consumes only when all of them allow.
    // Returns {0} when allowed, or {keyIndex, retryAfterSeconds} for the first refusing key.
    private static final RedisScript<List> SCRIPT = new DefaultRedisScript<>("""
            local now = tonumber(ARGV[1])
            local n = #KEYS
            local tokens = {}
            for i = 1, n do
              local base = 1 + (i - 1) * 3
              local kind = ARGV[base + 1]
              local limit = tonumber(ARGV[base + 2])
              local param = tonumber(ARGV[base + 3])
              if kind == 'b' then
                local h = redis.call('HMGET', KEYS[i], 't', 'ts')
                local t = tonumber(h[1])
                local ts = tonumber(h[2])
                if t == nil or ts == nil then
                  t = limit
                  ts = now
                end
                local elapsed = now - ts
                if elapsed < 0 then elapsed = 0 end
                t = math.min(limit, t + elapsed * limit / 60000)
                if t < 1 then
                  return {i, math.max(1, math.ceil((1 - t) * 60000 / limit / 1000))}
                end
                tokens[i] = t
              else
                local c = tonumber(redis.call('GET', KEYS[i]) or '0')
                if c >= limit then
                  return {i, math.max(1, param)}
                end
              end
            end
            for i = 1, n do
              local base = 1 + (i - 1) * 3
              local param = tonumber(ARGV[base + 3])
              if ARGV[base + 1] == 'b' then
                redis.call('HSET', KEYS[i], 't', tostring(tokens[i] - 1), 'ts', tostring(now))
                redis.call('PEXPIRE', KEYS[i], param)
              else
                local c = redis.call('INCR', KEYS[i])
                if c == 1 then
                  redis.call('EXPIRE', KEYS[i], param + %d)
                end
              end
            end
            return {0}
            """.formatted(DAY_TTL_SLACK_SECONDS), List.class);

    sealed interface Decision permits Allowed, Throttled {
    }

    record Allowed() implements Decision {
    }

    record Throttled(ApiConnectorLimitKind kind, long retryAfterSeconds) implements Decision {
    }

    static final Allowed ALLOWED = new Allowed();

    private final StringRedisTemplate redisTemplate;
    private final Clock clock;
    private final AtomicLong lastOutageWarnEpochSecond = new AtomicLong(-OUTAGE_WARN_INTERVAL_SECONDS);

    ApiConnectorRateLimiter(StringRedisTemplate redisTemplate, Clock clock) {
        this.redisTemplate = redisTemplate;
        this.clock = clock;
    }

    Decision tryAcquire(ApiConnectorEntity connector, UUID userId) {
        var perMinute = positive(connector.getCallsPerMinute());
        var perDay = positive(connector.getCallsPerDay());
        if (perMinute == null && perDay == null) {
            return ALLOWED;
        }
        var share = connector.getPerUserSharePercent();
        boolean perUser = share != null && share > 0 && share < 100 && userId != null;
        var now = clock.instant();
        var today = LocalDate.ofInstant(now, ZoneOffset.UTC);
        long secondsToNextDay = Math.max(1, Duration.between(now,
                today.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant()).toSeconds());
        var slot = KEY_PREFIX + "{" + connector.getId() + "}:";

        var keys = new ArrayList<String>();
        var kinds = new ArrayList<ApiConnectorLimitKind>();
        var args = new ArrayList<String>();
        args.add(Long.toString(now.toEpochMilli()));
        if (perMinute != null) {
            add(keys, kinds, args, slot + "min", ApiConnectorLimitKind.MINUTE, "b", perMinute, BUCKET_TTL_MS);
            if (perUser) {
                add(keys, kinds, args, slot + "min:" + userId, ApiConnectorLimitKind.USER_MINUTE, "b",
                        userShare(perMinute, share), BUCKET_TTL_MS);
            }
        }
        if (perDay != null) {
            add(keys, kinds, args, slot + "day:" + today, ApiConnectorLimitKind.DAY, "d", perDay,
                    secondsToNextDay);
            if (perUser) {
                add(keys, kinds, args, slot + "day:" + userId + ":" + today, ApiConnectorLimitKind.USER_DAY,
                        "d", userShare(perDay, share), secondsToNextDay);
            }
        }
        try {
            List<?> result = redisTemplate.execute(SCRIPT, keys, args.toArray());
            return toDecision(result, kinds);
        } catch (DataAccessException ex) {
            warnOutage(connector.getId(), now.getEpochSecond(), ex);
            return ALLOWED;
        }
    }

    static int userShare(int limit, int sharePercent) {
        return (int) Math.max(1, Math.ceil(limit * (double) sharePercent / 100));
    }

    private static Decision toDecision(List<?> result, List<ApiConnectorLimitKind> kinds) {
        if (result == null || result.isEmpty()) {
            return ALLOWED;
        }
        int index = ((Number) result.get(0)).intValue();
        if (index <= 0 || index > kinds.size() || result.size() < 2) {
            return ALLOWED;
        }
        long retryAfter = Math.max(1, ((Number) result.get(1)).longValue());
        return new Throttled(kinds.get(index - 1), retryAfter);
    }

    private static void add(List<String> keys, List<ApiConnectorLimitKind> kinds, List<String> args,
                            String key, ApiConnectorLimitKind kind, String type, long limit, long param) {
        keys.add(key);
        kinds.add(kind);
        args.add(type);
        args.add(Long.toString(limit));
        args.add(Long.toString(param));
    }

    private static Integer positive(Integer value) {
        return value != null && value > 0 ? value : null;
    }

    private void warnOutage(UUID connectorId, long epochSecond, DataAccessException ex) {
        long last = lastOutageWarnEpochSecond.get();
        if (epochSecond - last >= OUTAGE_WARN_INTERVAL_SECONDS
                && lastOutageWarnEpochSecond.compareAndSet(last, epochSecond)) {
            log.warn("Connector rate limiter unavailable (connector {}); allowing the call: {}",
                    connectorId, ex.getMessage());
        }
    }
}
