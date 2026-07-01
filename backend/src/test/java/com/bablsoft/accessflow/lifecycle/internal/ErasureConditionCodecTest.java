package com.bablsoft.accessflow.lifecycle.internal;

import com.bablsoft.accessflow.core.api.RowSecurityOperator;
import com.bablsoft.accessflow.lifecycle.api.ErasureCondition;
import com.bablsoft.accessflow.lifecycle.api.ErasureConditionSet;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ErasureConditionCodecTest {

    private final ErasureConditionCodec codec = new ErasureConditionCodec(new ObjectMapper());

    @Test
    void toJson_returnsNullForNullOrEmpty() {
        assertThat(codec.toJson(null)).isNull();
        assertThat(codec.toJson(ErasureConditionSet.empty())).isNull();
    }

    @Test
    void fromJson_returnsNullForNullOrBlank() {
        assertThat(codec.fromJson(null)).isNull();
        assertThat(codec.fromJson("   ")).isNull();
    }

    @Test
    void roundTripsAConditionSet() {
        var set = new ErasureConditionSet(List.of(
                new ErasureCondition("region", RowSecurityOperator.IN, List.of("EU", "US"), false),
                new ErasureCondition("deleted_at", RowSecurityOperator.IS_NULL, List.of(), true)));

        var json = codec.toJson(set);
        assertThat(json).contains("region").contains("EU");

        var decoded = codec.fromJson(json);
        assertThat(decoded).isNotNull();
        assertThat(decoded.conditions()).hasSize(2);
        assertThat(decoded.conditions().get(0).column()).isEqualTo("region");
        assertThat(decoded.conditions().get(0).operator()).isEqualTo(RowSecurityOperator.IN);
        assertThat(decoded.conditions().get(1).negate()).isTrue();
    }
}
