package com.bablsoft.accessflow.engine.redis;

import com.bablsoft.accessflow.core.api.ColumnMaskDirective;
import com.bablsoft.accessflow.core.api.ColumnMasker;
import com.bablsoft.accessflow.core.api.MaskingStrategy;
import com.bablsoft.accessflow.core.api.SelectExecutionResult;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RedisResultMapperTest {

    private final RedisResultMapper mapper = new RedisResultMapper();
    private static final Duration D = Duration.ofMillis(1);

    @Test
    void singleValueIsOneColumnOneRow() {
        var result = mapper.singleValue("hello", D, List.of(), List.of());
        assertThat(result.columns()).singleElement()
                .satisfies(c -> assertThat(c.name()).isEqualTo("value"));
        assertThat(result.rows()).containsExactly(java.util.Collections.singletonList("hello"));
        assertThat(result.rowCount()).isEqualTo(1);
    }

    @Test
    void mgetEmitsKeyValuePairs() {
        var result = mapper.keyValues(List.of("user:1", "user:2"), List.of("a", "b"), D,
                List.of(), List.of());
        assertThat(result.columns()).extracting("name").containsExactly("key", "value");
        assertThat(result.rows()).hasSize(2);
        assertThat(result.rows().get(0)).containsExactly("user:1", "a");
    }

    @Test
    void hashMapExposesFieldNamesAsColumns() {
        var hash = new LinkedHashMap<String, String>();
        hash.put("name", "Ada");
        hash.put("email", "ada@x.io");
        var result = mapper.hashMap(hash, D, List.of(), List.of());
        assertThat(result.columns()).extracting("name").containsExactly("name", "email");
        assertThat(result.rows()).singleElement()
                .satisfies(r -> assertThat(r).containsExactly("Ada", "ada@x.io"));
    }

    @Test
    void emptyHashYieldsNoColumnsNoRows() {
        var result = mapper.hashMap(Map.of(), D, List.of(), List.of());
        assertThat(result.columns()).isEmpty();
        assertThat(result.rows()).isEmpty();
        assertThat(result.rowCount()).isZero();
    }

    @Test
    void collectionCapsAtMaxRowsAndFlagsTruncation() {
        var result = mapper.collection(List.of("a", "b", "c"), 2, D, List.of(), List.of());
        assertThat(result.rows()).hasSize(2);
        assertThat(result.truncated()).isTrue();
    }

    @Test
    void keysForcesTruncationWhenMoreToScan() {
        var result = mapper.keys(List.of("k1", "k2"), true, 100, D, List.of(), List.of());
        assertThat(result.truncated()).isTrue();
        assertThat(result.columns()).singleElement()
                .satisfies(c -> assertThat(c.name()).isEqualTo("key"));
    }

    @Test
    void appliesFullMaskFromRestrictedColumns() {
        var result = mapper.singleValue("secret", D, List.of("value"), List.of());
        assertThat(result.rows().get(0).get(0)).isEqualTo(ColumnMasker.FULL_MASK);
        assertThat(result.columns().get(0).restricted()).isTrue();
    }

    @Test
    void appliesEmailMaskToHashFieldByPrefixedRef() {
        var policy = UUID.randomUUID();
        var mask = new ColumnMaskDirective("session.email", MaskingStrategy.EMAIL, Map.of(), policy);
        var hash = new LinkedHashMap<String, String>();
        hash.put("email", "ada@x.io");
        SelectExecutionResult result = mapper.hashMap(hash, D, List.of(), List.of(mask));
        assertThat(result.rows().get(0).get(0)).isEqualTo("a***@x.io");
        assertThat(result.appliedMaskingPolicyIds()).containsExactly(policy);
    }
}
