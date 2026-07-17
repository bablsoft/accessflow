package com.bablsoft.accessflow.proxy.internal;

import com.bablsoft.accessflow.core.api.ColumnMaskDirective;
import com.bablsoft.accessflow.core.api.DatasourceConnectionDescriptor;
import com.bablsoft.accessflow.core.api.DbType;
import com.bablsoft.accessflow.core.api.MaskingStrategy;
import com.bablsoft.accessflow.core.api.ReadReplicaEndpoint;
import com.bablsoft.accessflow.core.api.ResultColumn;
import com.bablsoft.accessflow.core.api.SelectExecutionResult;
import com.bablsoft.accessflow.core.api.SslMode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.sql.Types;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SelectResultCacheTest {

    private final UUID datasourceId = UUID.randomUUID();

    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ValueOperations<String, String> valueOps;
    @Mock
    private SetOperations<String, String> setOps;

    private SelectResultCache cache;

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(redisTemplate.opsForSet()).thenReturn(setOps);
        cache = new SelectResultCache(redisTemplate,
                new ProxyCacheProperties(true, Duration.ofSeconds(60), 1_000_000L));
    }

    private static SelectExecutionResult sampleResult() {
        return sampleResult(false, null);
    }

    private static SelectExecutionResult sampleResult(boolean truncated, String truncatedReason) {
        return new SelectExecutionResult(
                List.of(new ResultColumn("id", Types.BIGINT, "int8"),
                        new ResultColumn("email", Types.VARCHAR, "text", true)),
                List.of(List.of(1L, "a@example.com"), List.of(2L, "b@example.com")),
                2, truncated, Duration.ofMillis(12),
                Set.of(UUID.randomUUID()), Set.of(UUID.randomUUID()), truncatedReason);
    }

    private DatasourceConnectionDescriptor descriptor(boolean cacheEnabled, Integer ttlSeconds) {
        return new DatasourceConnectionDescriptor(datasourceId, UUID.randomUUID(),
                DbType.POSTGRESQL, "h", 5432, "db", "u", "ENC", SslMode.DISABLE, 10, 1000,
                false, null, false, null, null, null, List.<ReadReplicaEndpoint>of(), true, null,
                null, cacheEnabled, ttlSeconds);
    }

    @Test
    void enabledForRequiresGlobalSwitchAndDatasourceOptIn() {
        assertThat(cache.enabledFor(descriptor(true, null))).isTrue();
        assertThat(cache.enabledFor(descriptor(false, null))).isFalse();

        var disabled = new SelectResultCache(redisTemplate,
                new ProxyCacheProperties(false, null, null));
        assertThat(disabled.enabledFor(descriptor(true, null))).isFalse();
    }

    @Test
    void ttlFallsBackToDefaultWhenDatasourceHasNone() {
        assertThat(cache.ttlFor(descriptor(true, null))).isEqualTo(Duration.ofSeconds(60));
        assertThat(cache.ttlFor(descriptor(true, 120))).isEqualTo(Duration.ofSeconds(120));
    }

    @Test
    void putStoresJsonAndIndexesEveryReferencedTablePlusAllSet() {
        cache.put(datasourceId, "abc", Set.of("public.users"), Duration.ofSeconds(30),
                sampleResult());

        var keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(valueOps).set(keyCaptor.capture(), anyString(), eq(Duration.ofSeconds(30)));
        var valueKey = keyCaptor.getValue();
        assertThat(valueKey)
                .isEqualTo(SelectResultCache.VALUE_PREFIX + datasourceId + ":abc");
        verify(setOps).add(
                SelectResultCache.INDEX_PREFIX + datasourceId + ":public.users", valueKey);
        verify(setOps).add(
                SelectResultCache.INDEX_PREFIX + datasourceId + ":__all__", valueKey);
        verify(redisTemplate).expire(
                eq(SelectResultCache.INDEX_PREFIX + datasourceId + ":public.users"),
                eq(Duration.ofSeconds(90)));
    }

    @Test
    void getRoundTripsStoredResultWithFreshDuration() {
        var result = sampleResult();
        cache.put(datasourceId, "abc", Set.of("t"), Duration.ofSeconds(30), result);
        var jsonCaptor = ArgumentCaptor.forClass(String.class);
        verify(valueOps).set(anyString(), jsonCaptor.capture(), any(Duration.class));
        when(valueOps.get(SelectResultCache.VALUE_PREFIX + datasourceId + ":abc"))
                .thenReturn(jsonCaptor.getValue());

        var hit = cache.get(datasourceId, "abc", Duration.ofMillis(1));

        assertThat(hit).isPresent();
        var cached = hit.get();
        assertThat(cached.columns()).isEqualTo(result.columns());
        assertThat(cached.rowCount()).isEqualTo(2);
        assertThat(cached.truncated()).isFalse();
        assertThat(cached.truncatedReason()).isNull();
        assertThat(cached.duration()).isEqualTo(Duration.ofMillis(1));
        assertThat(cached.appliedMaskingPolicyIds())
                .isEqualTo(result.appliedMaskingPolicyIds());
        assertThat(cached.appliedRowSecurityPolicyIds())
                .isEqualTo(result.appliedRowSecurityPolicyIds());
        // JSON-natural types: numbers stay numbers, strings stay strings.
        assertThat(cached.rows().get(0).get(1)).isEqualTo("a@example.com");
        assertThat(((Number) cached.rows().get(0).get(0)).longValue()).isEqualTo(1L);
    }

    @Test
    void getRoundTripsTruncatedReason() {
        var result = sampleResult(true, SelectExecutionResult.TRUNCATED_BYTE_LIMIT);
        cache.put(datasourceId, "abc", Set.of("t"), Duration.ofSeconds(30), result);
        var jsonCaptor = ArgumentCaptor.forClass(String.class);
        verify(valueOps).set(anyString(), jsonCaptor.capture(), any(Duration.class));
        when(valueOps.get(SelectResultCache.VALUE_PREFIX + datasourceId + ":abc"))
                .thenReturn(jsonCaptor.getValue());

        var hit = cache.get(datasourceId, "abc", Duration.ofMillis(1));

        assertThat(hit).isPresent();
        assertThat(hit.get().truncated()).isTrue();
        assertThat(hit.get().truncatedReason())
                .isEqualTo(SelectExecutionResult.TRUNCATED_BYTE_LIMIT);
    }

    @Test
    void getReturnsEmptyOnMissAndOnRedisFailure() {
        when(valueOps.get(anyString())).thenReturn(null);
        assertThat(cache.get(datasourceId, "abc", Duration.ZERO)).isEmpty();

        when(valueOps.get(anyString())).thenThrow(new RuntimeException("redis down"));
        assertThat(cache.get(datasourceId, "abc", Duration.ZERO)).isEmpty();
    }

    @Test
    void putSkipsEntriesLargerThanMaxEntryBytes() {
        var tiny = new SelectResultCache(redisTemplate,
                new ProxyCacheProperties(true, Duration.ofSeconds(60), 10L));

        tiny.put(datasourceId, "abc", Set.of("t"), Duration.ofSeconds(30), sampleResult());

        verify(valueOps, never()).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    void putSwallowsRedisFailures() {
        org.mockito.Mockito.doThrow(new RuntimeException("redis down"))
                .when(valueOps).set(anyString(), anyString(), any(Duration.class));

        cache.put(datasourceId, "abc", Set.of("t"), Duration.ofSeconds(30), sampleResult());
        // no exception propagated
    }

    @Test
    void invalidateTablesDropsIndexedEntriesPerTable() {
        var indexKey = SelectResultCache.INDEX_PREFIX + datasourceId + ":public.users";
        when(setOps.members(indexKey)).thenReturn(Set.of("k1", "k2"));

        cache.invalidateTables(datasourceId, Set.of("public.users"));

        verify(redisTemplate).delete(Set.of("k1", "k2"));
        verify(redisTemplate).delete(indexKey);
    }

    @Test
    void invalidateTablesWithEmptySetPurgesWholeDatasource() {
        var allKey = SelectResultCache.INDEX_PREFIX + datasourceId + ":__all__";
        when(setOps.members(allKey)).thenReturn(Set.of("k1"));

        cache.invalidateTables(datasourceId, Set.of());

        verify(redisTemplate).delete(Set.of("k1"));
        verify(redisTemplate).delete(allKey);
    }

    @Test
    void invalidateSwallowsRedisFailures() {
        when(setOps.members(anyString())).thenThrow(new RuntimeException("redis down"));

        cache.invalidateTables(datasourceId, Set.of("t"));
        cache.invalidateAll(datasourceId);
        // no exception propagated
    }

    @Test
    void cacheKeyIsStableAndOrderInsensitiveForDirectives() {
        var maskA = new ColumnMaskDirective("users.email", MaskingStrategy.FULL, Map.of(),
                UUID.fromString("00000000-0000-0000-0000-000000000001"));
        var maskB = new ColumnMaskDirective("users.ssn", MaskingStrategy.FULL, Map.of(),
                UUID.fromString("00000000-0000-0000-0000-000000000002"));

        var key1 = SelectResultCache.cacheKey("SELECT 1", List.of(42), List.of("a", "b"),
                List.of(maskA, maskB), 100);
        var key2 = SelectResultCache.cacheKey("SELECT 1", List.of(42), List.of("b", "a"),
                List.of(maskB, maskA), 100);

        assertThat(key1).isEqualTo(key2).hasSize(64);
    }

    @Test
    void cacheKeyDiscriminatesSecurityRelevantInputs() {
        var base = SelectResultCache.cacheKey("SELECT 1", List.of(), List.of(), List.of(), 100);

        assertThat(SelectResultCache.cacheKey("SELECT 2", List.of(), List.of(), List.of(), 100))
                .isNotEqualTo(base);
        assertThat(SelectResultCache.cacheKey("SELECT 1", List.of(7), List.of(), List.of(), 100))
                .isNotEqualTo(base);
        assertThat(SelectResultCache.cacheKey("SELECT 1", List.of(), List.of("users.ssn"),
                List.of(), 100)).isNotEqualTo(base);
        assertThat(SelectResultCache.cacheKey("SELECT 1", List.of(), List.of(),
                List.of(new ColumnMaskDirective("users.email", MaskingStrategy.FULL, Map.of(),
                        null)), 100)).isNotEqualTo(base);
        assertThat(SelectResultCache.cacheKey("SELECT 1", List.of(), List.of(), List.of(), 50))
                .isNotEqualTo(base);
        // Bind type participates in the key ("1" as String vs 1 as Long).
        assertThat(SelectResultCache.cacheKey("SELECT 1", List.of("1"), List.of(), List.of(), 100))
                .isNotEqualTo(
                        SelectResultCache.cacheKey("SELECT 1", List.of(1L), List.of(), List.of(), 100));
    }
}
