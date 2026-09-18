package com.bablsoft.accessflow.serviceaccounts.internal;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.bablsoft.accessflow.serviceaccounts.api.ServiceAccountRateLimitExceededException;
import com.bablsoft.accessflow.serviceaccounts.internal.config.ServiceAccountRateLimitProperties;
import com.bablsoft.accessflow.serviceaccounts.internal.persistence.entity.ServiceAccountEntity;
import com.bablsoft.accessflow.serviceaccounts.internal.persistence.repo.ServiceAccountRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultServiceAccountRateLimiterTest {

    // 12:00:30Z -> 30 s left in the minute window, 12 h left in the day window.
    private static final Instant NOW = Instant.parse("2026-06-15T12:00:30Z");
    private static final long EPOCH_MINUTE = NOW.getEpochSecond() / 60;
    private static final long EPOCH_DAY = NOW.getEpochSecond() / 86_400;

    @Mock StringRedisTemplate redisTemplate;
    @Mock ValueOperations<String, String> valueOps;
    @Mock ServiceAccountRepository repository;
    @Mock Clock clock;

    private final UUID userId = UUID.randomUUID();
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private final Logger logger = (Logger) LoggerFactory.getLogger(DefaultServiceAccountRateLimiter.class);

    @BeforeEach
    void attachAppender() {
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        logger.detachAppender(appender);
    }

    private DefaultServiceAccountRateLimiter limiter(int perMinute, int perDay) {
        return new DefaultServiceAccountRateLimiter(redisTemplate,
                new ServiceAccountRateLimitProperties(perMinute, perDay), repository, clock);
    }

    private void noDetailRow() {
        when(repository.findById(userId)).thenReturn(Optional.empty());
    }

    private void detailRow(Integer perMinute, Integer perDay) {
        var account = new ServiceAccountEntity();
        account.setUserId(userId);
        account.setRateLimitPerMinute(perMinute);
        account.setRateLimitPerDay(perDay);
        when(repository.findById(userId)).thenReturn(Optional.of(account));
    }

    private void redisReturns(Long... counts) {
        when(clock.instant()).thenReturn(NOW);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        var stub = when(valueOps.increment(anyString()));
        for (Long count : counts) {
            stub = stub.thenReturn(count);
        }
    }

    @Test
    void nullUserIsNoOp() {
        limiter(120, 0).enforce(null);
        verifyNoInteractions(redisTemplate, repository);
    }

    @Test
    void bothLimitsDisabledNeverTouchRedis() {
        noDetailRow();
        limiter(0, 0).enforce(userId);
        verifyNoInteractions(redisTemplate);
    }

    @Test
    void firstRequestInWindowSetsTheMinuteTtl() {
        noDetailRow();
        redisReturns(1L);

        limiter(120, 0).enforce(userId);

        var key = ArgumentCaptor.forClass(String.class);
        verify(valueOps).increment(key.capture());
        assertThat(key.getValue())
                .isEqualTo("accessflow:serviceaccounts:ratelimit:" + userId + ":" + EPOCH_MINUTE);
        verify(redisTemplate).expire(key.getValue(), Duration.ofMinutes(1));
    }

    @Test
    void requestAtTheLimitIsAllowedWithoutResettingTheTtl() {
        noDetailRow();
        redisReturns(120L);

        assertThatCode(() -> limiter(120, 0).enforce(userId)).doesNotThrowAnyException();
        verify(redisTemplate, never()).expire(anyString(), any(Duration.class));
    }

    @Test
    void requestOverTheMinuteLimitThrowsWithTheRemainingWindow() {
        noDetailRow();
        redisReturns(121L);

        assertThatThrownBy(() -> limiter(120, 0).enforce(userId))
                .isInstanceOfSatisfying(ServiceAccountRateLimitExceededException.class, ex -> {
                    assertThat(ex.limit()).isEqualTo(120);
                    assertThat(ex.retryAfterSeconds()).isEqualTo(30L);
                    assertThat(ex.window()).isEqualTo("minute");
                });
    }

    @Test
    void retryAfterIsNeverBelowOneSecond() {
        noDetailRow();
        when(clock.instant()).thenReturn(Instant.parse("2026-06-15T12:00:00Z"));
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.increment(anyString())).thenReturn(5L);

        assertThatThrownBy(() -> limiter(1, 0).enforce(userId))
                .isInstanceOfSatisfying(ServiceAccountRateLimitExceededException.class,
                        ex -> assertThat(ex.retryAfterSeconds()).isBetween(1L, 60L));
    }

    @Test
    void countersAreIsolatedPerIdentity() {
        var otherUser = UUID.randomUUID();
        when(repository.findById(any(UUID.class))).thenReturn(Optional.empty());
        redisReturns(1L, 1L);

        var limiter = limiter(120, 0);
        limiter.enforce(userId);
        limiter.enforce(otherUser);

        var keys = ArgumentCaptor.forClass(String.class);
        verify(valueOps, org.mockito.Mockito.times(2)).increment(keys.capture());
        assertThat(keys.getAllValues())
                .containsExactly(
                        "accessflow:serviceaccounts:ratelimit:" + userId + ":" + EPOCH_MINUTE,
                        "accessflow:serviceaccounts:ratelimit:" + otherUser + ":" + EPOCH_MINUTE);
    }

    @Test
    void detailRowLimitBeatsTheConfiguredDefault() {
        detailRow(2, null);
        redisReturns(3L);

        assertThatThrownBy(() -> limiter(120, 0).enforce(userId))
                .isInstanceOfSatisfying(ServiceAccountRateLimitExceededException.class,
                        ex -> assertThat(ex.limit()).isEqualTo(2));
    }

    @Test
    void nullDetailRowLimitFallsBackToTheConfiguredDefault() {
        detailRow(null, null);
        redisReturns(121L);

        assertThatThrownBy(() -> limiter(120, 0).enforce(userId))
                .isInstanceOfSatisfying(ServiceAccountRateLimitExceededException.class,
                        ex -> assertThat(ex.limit()).isEqualTo(120));
    }

    @Test
    void nonPositiveConfiguredMinuteLimitDisablesTheMinuteWindowOnly() {
        noDetailRow();
        redisReturns(1L);

        limiter(0, 500).enforce(userId);

        var key = ArgumentCaptor.forClass(String.class);
        verify(valueOps).increment(key.capture());
        assertThat(key.getValue())
                .isEqualTo("accessflow:serviceaccounts:ratelimit:" + userId + ":d:" + EPOCH_DAY);
        verify(redisTemplate).expire(key.getValue(), Duration.ofDays(1));
    }

    @Test
    void dailyWindowOverLimitThrowsWithSecondsUntilMidnight() {
        noDetailRow();
        // Minute window at 1, day window over its cap of 500.
        redisReturns(1L, 501L);

        assertThatThrownBy(() -> limiter(120, 500).enforce(userId))
                .isInstanceOfSatisfying(ServiceAccountRateLimitExceededException.class, ex -> {
                    assertThat(ex.limit()).isEqualTo(500);
                    assertThat(ex.window()).isEqualTo("day");
                    assertThat(ex.retryAfterSeconds()).isEqualTo(Duration.ofHours(12).minusSeconds(30).toSeconds());
                });
    }

    @Test
    void minuteWindowIsCheckedBeforeTheDailyWindow() {
        noDetailRow();
        redisReturns(121L);

        assertThatThrownBy(() -> limiter(120, 500).enforce(userId))
                .isInstanceOfSatisfying(ServiceAccountRateLimitExceededException.class,
                        ex -> assertThat(ex.window()).isEqualTo("minute"));
        verify(valueOps).increment(anyString());
    }

    @Test
    void redisOutageFailsOpenAndWarnsOnce() {
        noDetailRow();
        when(clock.instant()).thenReturn(NOW);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.increment(anyString())).thenThrow(new RedisConnectionFailureException("redis down"));

        var limiter = limiter(120, 0);
        assertThatCode(() -> limiter.enforce(userId)).doesNotThrowAnyException();
        assertThatCode(() -> limiter.enforce(userId)).doesNotThrowAnyException();

        var warnings = appender.list.stream().filter(e -> e.getLevel() == Level.WARN).toList();
        assertThat(warnings).hasSize(1);
        assertThat(warnings.getFirst().getFormattedMessage())
                .contains("fail-open").contains(userId.toString()).contains("redis down");
    }

    @Test
    void outageWarningIsRepeatedAfterTheThrottleWindow() {
        noDetailRow();
        when(clock.instant()).thenReturn(NOW, NOW, NOW.plusSeconds(61), NOW.plusSeconds(61));
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.increment(anyString())).thenThrow(new RedisConnectionFailureException("redis down"));

        var limiter = limiter(120, 0);
        limiter.enforce(userId);
        limiter.enforce(userId);

        assertThat(appender.list.stream().filter(e -> e.getLevel() == Level.WARN)).hasSize(2);
    }

    @Test
    void databaseOutageOnTheLimitLookupAlsoFailsOpen() {
        // An unreachable Postgres / exhausted pool surfaces from the repository's read-only
        // transaction as CannotCreateTransactionException (a TransactionException, not a
        // DataAccessException); a statement timeout on a live connection is the DataAccessException.
        when(repository.findById(userId))
                .thenThrow(new CannotCreateTransactionException("pool exhausted"))
                .thenThrow(new QueryTimeoutException("pg timeout"));
        when(clock.instant()).thenReturn(NOW);

        var limiter = limiter(120, 0);
        assertThatCode(() -> limiter.enforce(userId)).doesNotThrowAnyException();
        assertThatCode(() -> limiter.enforce(userId)).doesNotThrowAnyException();
        verifyNoInteractions(redisTemplate);
        assertThat(appender.list.stream().filter(e -> e.getLevel() == Level.WARN)).hasSize(1);
    }

    @Test
    void exceededWarningIsThrottledPerMinute() {
        noDetailRow();
        when(clock.instant()).thenReturn(NOW, NOW, NOW.plusSeconds(61));
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.increment(anyString())).thenReturn(121L);
        var limiter = limiter(120, 0);

        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(() -> limiter.enforce(userId))
                    .isInstanceOf(ServiceAccountRateLimitExceededException.class);
        }

        assertThat(appender.list.stream().filter(e -> e.getLevel() == Level.WARN)).hasSize(2);
    }
}
