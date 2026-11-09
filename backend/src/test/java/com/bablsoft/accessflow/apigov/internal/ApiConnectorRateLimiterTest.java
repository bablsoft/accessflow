package com.bablsoft.accessflow.apigov.internal;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.bablsoft.accessflow.apigov.api.ApiConnectorLimitKind;
import com.bablsoft.accessflow.apigov.internal.persistence.entity.ApiConnectorEntity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ApiConnectorRateLimiterTest {

    private static final Instant NOW = Instant.parse("2026-11-09T23:59:30Z");

    @Mock
    private StringRedisTemplate redisTemplate;

    private ApiConnectorRateLimiter limiter;
    private final UUID connectorId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final Logger logger = (Logger) LoggerFactory.getLogger(ApiConnectorRateLimiter.class);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    @BeforeEach
    void setUp() {
        limiter = new ApiConnectorRateLimiter(redisTemplate, Clock.fixed(NOW, ZoneOffset.UTC));
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
    }

    private ApiConnectorEntity connector(Integer perMinute, Integer perDay, Integer share) {
        var c = new ApiConnectorEntity();
        c.setId(connectorId);
        c.setCallsPerMinute(perMinute);
        c.setCallsPerDay(perDay);
        c.setPerUserSharePercent(share);
        return c;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void scriptReturns(List<Long> result) {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenReturn((List) result);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private ArgumentCaptor<List<String>> captureKeys(List<Long> result) {
        ArgumentCaptor<List<String>> keys = ArgumentCaptor.forClass((Class) List.class);
        when(redisTemplate.execute(any(RedisScript.class), keys.capture(), any(Object[].class)))
                .thenReturn((List) result);
        return keys;
    }

    @Test
    void unlimitedConnectorNeverCallsRedis() {
        assertThat(limiter.tryAcquire(connector(null, null, 50), userId))
                .isInstanceOf(ApiConnectorRateLimiter.Allowed.class);
        assertThat(limiter.tryAcquire(connector(0, 0, null), userId))
                .isInstanceOf(ApiConnectorRateLimiter.Allowed.class);
        verifyNoInteractions(redisTemplate);
    }

    @Test
    void allowedWhenTheScriptReturnsZero() {
        var keys = captureKeys(List.of(0L));
        assertThat(limiter.tryAcquire(connector(10, null, null), userId))
                .isInstanceOf(ApiConnectorRateLimiter.Allowed.class);
        assertThat(keys.getValue()).containsExactly(
                ApiConnectorRateLimiter.KEY_PREFIX + "{" + connectorId + "}:min");
    }

    @Test
    void perUserKeysOnlyWhenAShareIsSet() {
        var keys = captureKeys(List.of(0L));
        limiter.tryAcquire(connector(10, 100, 25), userId);
        var slot = ApiConnectorRateLimiter.KEY_PREFIX + "{" + connectorId + "}:";
        assertThat(keys.getValue()).containsExactly(slot + "min", slot + "min:" + userId,
                slot + "day:2026-11-09", slot + "day:" + userId + ":2026-11-09");
    }

    @Test
    void minuteDenialMapsToMinute() {
        scriptReturns(List.of(1L, 6L));
        assertThat(limiter.tryAcquire(connector(10, 100, 25), userId))
                .isEqualTo(new ApiConnectorRateLimiter.Throttled(ApiConnectorLimitKind.MINUTE, 6));
    }

    @Test
    void userMinuteDenialMapsToUserMinute() {
        scriptReturns(List.of(2L, 12L));
        assertThat(limiter.tryAcquire(connector(10, 100, 25), userId))
                .isEqualTo(new ApiConnectorRateLimiter.Throttled(ApiConnectorLimitKind.USER_MINUTE, 12));
    }

    @Test
    void dayDenialMapsToDay() {
        scriptReturns(List.of(3L, 30L));
        assertThat(limiter.tryAcquire(connector(10, 100, 25), userId))
                .isEqualTo(new ApiConnectorRateLimiter.Throttled(ApiConnectorLimitKind.DAY, 30));
    }

    @Test
    void userDayDenialMapsToUserDay() {
        scriptReturns(List.of(4L, 30L));
        assertThat(limiter.tryAcquire(connector(10, 100, 25), userId))
                .isEqualTo(new ApiConnectorRateLimiter.Throttled(ApiConnectorLimitKind.USER_DAY, 30));
    }

    @Test
    void dayOnlyConnectorMapsIndexOneToDay() {
        scriptReturns(List.of(1L, 30L));
        assertThat(limiter.tryAcquire(connector(null, 5, null), userId))
                .isEqualTo(new ApiConnectorRateLimiter.Throttled(ApiConnectorLimitKind.DAY, 30));
    }

    @Test
    void retryAfterIsNeverBelowOneSecond() {
        scriptReturns(List.of(1L, 0L));
        assertThat(limiter.tryAcquire(connector(10, null, null), userId))
                .isEqualTo(new ApiConnectorRateLimiter.Throttled(ApiConnectorLimitKind.MINUTE, 1));
    }

    @Test
    void malformedScriptResultIsAllowed() {
        scriptReturns(List.of(9L, 5L));
        assertThat(limiter.tryAcquire(connector(10, null, null), userId))
                .isInstanceOf(ApiConnectorRateLimiter.Allowed.class);
    }

    @Test
    void nullOrEmptyScriptResultIsAllowed() {
        scriptReturns(null);
        assertThat(limiter.tryAcquire(connector(10, null, null), userId))
                .isInstanceOf(ApiConnectorRateLimiter.Allowed.class);
        scriptReturns(List.of());
        assertThat(limiter.tryAcquire(connector(10, null, null), userId))
                .isInstanceOf(ApiConnectorRateLimiter.Allowed.class);
    }

    @Test
    void shareOfHundredOrMissingUserAddsNoUserKeys() {
        var keys = captureKeys(List.of(0L));
        limiter.tryAcquire(connector(10, null, 100), userId);
        assertThat(keys.getValue()).hasSize(1);
        limiter.tryAcquire(connector(10, null, 50), null);
        assertThat(keys.getAllValues().get(1)).hasSize(1);
    }

    @Test
    @SuppressWarnings("unchecked")
    void redisFailureFailsOpenAndWarnsOnce() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenThrow(new QueryTimeoutException("redis down"));

        assertThat(limiter.tryAcquire(connector(10, null, null), userId))
                .isInstanceOf(ApiConnectorRateLimiter.Allowed.class);
        assertThat(limiter.tryAcquire(connector(10, null, null), userId))
                .isInstanceOf(ApiConnectorRateLimiter.Allowed.class);

        assertThat(appender.list).filteredOn(e -> e.getLevel() == Level.WARN).hasSize(1);
    }

    @Test
    void userShareRoundsUpAndIsAtLeastOne() {
        assertThat(ApiConnectorRateLimiter.userShare(10, 25)).isEqualTo(3);
        assertThat(ApiConnectorRateLimiter.userShare(1, 1)).isEqualTo(1);
        assertThat(ApiConnectorRateLimiter.userShare(200, 50)).isEqualTo(100);
    }
}
