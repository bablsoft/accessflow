package com.bablsoft.accessflow.schemachange.api;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;

import static org.assertj.core.api.Assertions.assertThat;

/** The terminal set mirrors the {@code uq_schema_change_set_promotions_open} partial index. */
class SchemaChangePromotionStatusTest {

    @Test
    void openStatesAreNotTerminal() {
        assertThat(SchemaChangePromotionStatus.PENDING.isTerminal()).isFalse();
        assertThat(SchemaChangePromotionStatus.IN_REVIEW.isTerminal()).isFalse();
        assertThat(SchemaChangePromotionStatus.APPROVED.isTerminal()).isFalse();
    }

    @Test
    void outcomesAreTerminal() {
        assertThat(SchemaChangePromotionStatus.APPLIED.isTerminal()).isTrue();
        assertThat(SchemaChangePromotionStatus.FAILED.isTerminal()).isTrue();
        assertThat(SchemaChangePromotionStatus.PARTIALLY_APPLIED.isTerminal()).isTrue();
        assertThat(SchemaChangePromotionStatus.CANCELLED.isTerminal()).isTrue();
    }

    @Test
    void everyValueIsClassified() {
        var terminal = EnumSet.allOf(SchemaChangePromotionStatus.class).stream()
                .filter(SchemaChangePromotionStatus::isTerminal)
                .toList();

        assertThat(terminal).hasSize(4);
        assertThat(SchemaChangePromotionStatus.values()).hasSize(7);
    }
}
