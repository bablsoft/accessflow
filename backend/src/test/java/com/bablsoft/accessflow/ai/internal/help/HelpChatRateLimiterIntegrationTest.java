package com.bablsoft.accessflow.ai.internal.help;

import com.bablsoft.accessflow.TestcontainersConfig;
import com.bablsoft.accessflow.ai.api.AiRateLimitExceededException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The per-user help limit against a real Redis rather than a mocked template, because the two claims
 * that matter are claims about Redis: that the window is <em>fixed</em> (the key expires on its own
 * schedule and a later request does not push it forward), and that the counter is shared, so a user
 * who exceeds the limit in one process is limited in the next one too.
 *
 * <p>Time is injected rather than waited for — a test that slept a minute to cross a window boundary
 * would be a minute of CI for one assertion.
 */
@SpringBootTest
@ImportTestcontainers(TestcontainersConfig.class)
class HelpChatRateLimiterIntegrationTest {

    private static final Instant IN_WINDOW = Instant.parse("2026-09-07T12:00:30Z");
    private static final Instant NEXT_WINDOW = Instant.parse("2026-09-07T12:01:05Z");

    @Autowired StringRedisTemplate redisTemplate;

    private final UUID orgId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();

    private HelpChatRateLimiter limiterAt(Instant instant) {
        return new HelpChatRateLimiter(redisTemplate, Clock.fixed(instant, ZoneOffset.UTC));
    }

    @Test
    void allowsUpToTheLimitThenRefusesWithinTheSameWindow() {
        var limiter = limiterAt(IN_WINDOW);

        for (int i = 0; i < 3; i++) {
            assertThatCode(() -> limiter.enforce(orgId, userId, 3)).doesNotThrowAnyException();
        }

        assertThatThrownBy(() -> limiter.enforce(orgId, userId, 3))
                .isInstanceOfSatisfying(AiRateLimitExceededException.class, ex -> {
                    assertThat(ex.limit()).isEqualTo(3);
                    assertThat(ex.retryAfterSeconds()).isEqualTo(60L);
                });
    }

    @Test
    void theNextMinuteIsAFreshWindow() {
        limiterAt(IN_WINDOW).enforce(orgId, userId, 1);
        assertThatThrownBy(() -> limiterAt(IN_WINDOW).enforce(orgId, userId, 1))
                .isInstanceOf(AiRateLimitExceededException.class);

        assertThatCode(() -> limiterAt(NEXT_WINDOW).enforce(orgId, userId, 1))
                .doesNotThrowAnyException();
    }

    @Test
    void oneUserExhaustingTheirWindowDoesNotLimitAnother() {
        var limiter = limiterAt(IN_WINDOW);
        limiter.enforce(orgId, userId, 1);

        assertThatCode(() -> limiter.enforce(orgId, UUID.randomUUID(), 1))
                .doesNotThrowAnyException();
    }

    /**
     * The TTL is set on the first increment only. Setting it on every request would slide the window
     * forward for as long as a user kept asking, so the counter would never reset while it mattered;
     * setting it never would leak a key per user per minute forever.
     */
    @Test
    void expiryIsSetOnTheFirstIncrementAndNotPushedForwardByLaterOnes() {
        var limiter = limiterAt(IN_WINDOW);
        limiter.enforce(orgId, userId, 5);
        var key = key(IN_WINDOW);

        assertThat(redisTemplate.getExpire(key)).isPositive().isLessThanOrEqualTo(60L);

        // Shorten the TTL, then ask again: a limiter that re-expired on every call would push it
        // back to 60 and the window would never close.
        redisTemplate.expire(key, Duration.ofSeconds(5));
        limiter.enforce(orgId, userId, 5);

        assertThat(redisTemplate.getExpire(key)).isLessThanOrEqualTo(5L);
    }

    @Test
    void aNonPositiveLimitDisablesTheCheckAndWritesNothing() {
        limiterAt(IN_WINDOW).enforce(orgId, userId, 0);

        assertThat(redisTemplate.hasKey(key(IN_WINDOW))).isFalse();
    }

    @Test
    void aMissingOrganizationOrUserIsANoOp() {
        assertThatCode(() -> {
            limiterAt(IN_WINDOW).enforce(null, userId, 5);
            limiterAt(IN_WINDOW).enforce(orgId, null, 5);
        }).doesNotThrowAnyException();
    }

    private String key(Instant instant) {
        return HelpChatRateLimiter.KEY_PREFIX + orgId + ":" + userId + ":"
                + (instant.getEpochSecond() / 60);
    }
}
