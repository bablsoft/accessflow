package com.bablsoft.accessflow.schemachange.internal;

import com.bablsoft.accessflow.requestgroups.api.RequestGroupStatus;
import com.bablsoft.accessflow.schemachange.api.SchemaChangePromotionStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class SchemaChangePromotionStatusMapperTest {

    @ParameterizedTest
    @CsvSource({
            "PENDING_REVIEW,IN_REVIEW",
            "APPROVED,APPROVED",
            "EXECUTED,APPLIED",
            "PARTIALLY_EXECUTED,PARTIALLY_APPLIED",
            "FAILED,FAILED",
            "REJECTED,CANCELLED",
            "TIMED_OUT,CANCELLED",
            "CANCELLED,CANCELLED"
    })
    void mapsEveryProjectedGroupStatus(RequestGroupStatus group, SchemaChangePromotionStatus promotion) {
        assertThat(SchemaChangePromotionStatusMapper.map(group)).contains(promotion);
    }

    @ParameterizedTest
    @EnumSource(value = RequestGroupStatus.class, names = {"DRAFT", "PENDING_AI", "EXECUTING"})
    void ignoresTheGroupStatesWithNoPromotionCounterpart(RequestGroupStatus group) {
        assertThat(SchemaChangePromotionStatusMapper.map(group)).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(RequestGroupStatus.class)
    void everyGroupStatusIsDecidedRatherThanThrowing(RequestGroupStatus group) {
        assertThat(SchemaChangePromotionStatusMapper.map(group)).isNotNull();
    }

    @Test
    void advancesOnlyForwards() {
        assertThat(SchemaChangePromotionStatusMapper.advances(SchemaChangePromotionStatus.PENDING,
                SchemaChangePromotionStatus.IN_REVIEW)).isTrue();
        assertThat(SchemaChangePromotionStatusMapper.advances(SchemaChangePromotionStatus.IN_REVIEW,
                SchemaChangePromotionStatus.APPROVED)).isTrue();
        assertThat(SchemaChangePromotionStatusMapper.advances(SchemaChangePromotionStatus.APPROVED,
                SchemaChangePromotionStatus.APPLIED)).isTrue();
        assertThat(SchemaChangePromotionStatusMapper.advances(SchemaChangePromotionStatus.PENDING,
                SchemaChangePromotionStatus.APPLIED)).isTrue();
    }

    @Test
    void neverMovesBackwardsOrSideways() {
        assertThat(SchemaChangePromotionStatusMapper.advances(SchemaChangePromotionStatus.APPROVED,
                SchemaChangePromotionStatus.IN_REVIEW)).isFalse();
        assertThat(SchemaChangePromotionStatusMapper.advances(SchemaChangePromotionStatus.IN_REVIEW,
                SchemaChangePromotionStatus.IN_REVIEW)).isFalse();
    }

    @ParameterizedTest
    @EnumSource(value = SchemaChangePromotionStatus.class,
            names = {"APPLIED", "FAILED", "PARTIALLY_APPLIED", "CANCELLED"})
    void aTerminalStatusIsFinal(SchemaChangePromotionStatus terminal) {
        for (var next : SchemaChangePromotionStatus.values()) {
            assertThat(SchemaChangePromotionStatusMapper.advances(terminal, next)).isFalse();
        }
    }

    @Test
    void mapReturnsAnOptionalRatherThanNull() {
        assertThat(SchemaChangePromotionStatusMapper.map(RequestGroupStatus.DRAFT))
                .isInstanceOf(Optional.class).isEmpty();
    }
}
