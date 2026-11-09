package com.bablsoft.accessflow.apigov.internal;

import com.bablsoft.accessflow.TestcontainersConfig;
import com.bablsoft.accessflow.apigov.api.ApiConnectorLimitKind;
import com.bablsoft.accessflow.apigov.internal.persistence.entity.ApiConnectorEntity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/** #972: the token-bucket / daily-counter Lua script against the Testcontainers Redis. */
@SpringBootTest
@ImportTestcontainers(TestcontainersConfig.class)
class ApiConnectorRateLimiterIntegrationTest {

    @Autowired
    private StringRedisTemplate redisTemplate;

    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advanceSeconds(long seconds) {
            now = now.plusSeconds(seconds);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private static ApiConnectorEntity connector(Integer perMinute, Integer perDay, Integer share) {
        var c = new ApiConnectorEntity();
        c.setId(UUID.randomUUID());
        c.setCallsPerMinute(perMinute);
        c.setCallsPerDay(perDay);
        c.setPerUserSharePercent(share);
        return c;
    }

    @Test
    void minuteBucketAdmitsItsBurstThenRefusesAndRefills() {
        var clock = new MutableClock(Instant.parse("2026-11-09T10:00:00Z"));
        var limiter = new ApiConnectorRateLimiter(redisTemplate, clock);
        var c = connector(2, null, null);
        var user = UUID.randomUUID();

        assertThat(limiter.tryAcquire(c, user)).isInstanceOf(ApiConnectorRateLimiter.Allowed.class);
        assertThat(limiter.tryAcquire(c, user)).isInstanceOf(ApiConnectorRateLimiter.Allowed.class);
        var third = limiter.tryAcquire(c, user);
        assertThat(third).isInstanceOfSatisfying(ApiConnectorRateLimiter.Throttled.class, t -> {
            assertThat(t.kind()).isEqualTo(ApiConnectorLimitKind.MINUTE);
            assertThat(t.retryAfterSeconds()).isEqualTo(30);
        });

        clock.advanceSeconds(30);
        assertThat(limiter.tryAcquire(c, user)).isInstanceOf(ApiConnectorRateLimiter.Allowed.class);
        assertThat(limiter.tryAcquire(c, user)).isInstanceOf(ApiConnectorRateLimiter.Throttled.class);
    }

    @Test
    void dayRefusalDoesNotBurnAMinuteToken() {
        var clock = new MutableClock(Instant.parse("2026-11-09T23:59:00Z"));
        var limiter = new ApiConnectorRateLimiter(redisTemplate, clock);
        var c = connector(1, 1, null);
        var user = UUID.randomUUID();

        assertThat(limiter.tryAcquire(c, user)).isInstanceOf(ApiConnectorRateLimiter.Allowed.class);
        clock.advanceSeconds(60);
        // The next UTC day starts a fresh daily counter.
        assertThat(limiter.tryAcquire(c, user)).isInstanceOf(ApiConnectorRateLimiter.Allowed.class);

        var d = connector(5, 1, null);
        assertThat(limiter.tryAcquire(d, user)).isInstanceOf(ApiConnectorRateLimiter.Allowed.class);
        var refused = limiter.tryAcquire(d, user);
        assertThat(refused).isInstanceOfSatisfying(ApiConnectorRateLimiter.Throttled.class, t -> {
            assertThat(t.kind()).isEqualTo(ApiConnectorLimitKind.DAY);
            assertThat(t.retryAfterSeconds()).isEqualTo(86_400);
        });
        var tokens = Double.parseDouble((String) redisTemplate.opsForHash()
                .get(ApiConnectorRateLimiter.KEY_PREFIX + "{" + d.getId() + "}:min", "t"));
        assertThat(tokens).isEqualTo(4.0);
    }

    @Test
    void perUserShareThrottlesOneUserButNotAnother() {
        var limiter = new ApiConnectorRateLimiter(redisTemplate,
                Clock.fixed(Instant.parse("2026-11-09T10:00:00Z"), ZoneOffset.UTC));
        var c = connector(10, null, 10);
        var alice = UUID.randomUUID();
        var bob = UUID.randomUUID();

        assertThat(limiter.tryAcquire(c, alice)).isInstanceOf(ApiConnectorRateLimiter.Allowed.class);
        assertThat(limiter.tryAcquire(c, alice)).isInstanceOfSatisfying(ApiConnectorRateLimiter.Throttled.class,
                t -> assertThat(t.kind()).isEqualTo(ApiConnectorLimitKind.USER_MINUTE));
        assertThat(limiter.tryAcquire(c, bob)).isInstanceOf(ApiConnectorRateLimiter.Allowed.class);
    }

    @Test
    void racingCallersForTheLastTokenSeeExactlyOneAllowed() throws Exception {
        var limiter = new ApiConnectorRateLimiter(redisTemplate,
                Clock.fixed(Instant.parse("2026-11-09T10:00:00Z"), ZoneOffset.UTC));
        var c = connector(1, null, null);
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var futures = new ArrayList<Future<ApiConnectorRateLimiter.Decision>>();
            for (int i = 0; i < 2; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return limiter.tryAcquire(c, UUID.randomUUID());
                }));
            }
            start.countDown();
            var allowed = 0;
            for (var f : futures) {
                if (f.get() instanceof ApiConnectorRateLimiter.Allowed) {
                    allowed++;
                }
            }
            assertThat(allowed).isEqualTo(1);
        }
    }
}
