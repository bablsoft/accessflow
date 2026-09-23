package com.bablsoft.accessflow.schemachange.internal;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class SchemaDriftScanReasonTest {

    /**
     * These land verbatim in a stored column and are rendered by the UI, so they are a wire contract
     * rather than prose: renaming one silently breaks every row already written.
     */
    @Test
    void everyReasonIsAStableUpperSnakeCodeMatchingItsConstantName() {
        var constants = Arrays.stream(SchemaDriftScanReason.class.getDeclaredFields())
                .filter(f -> Modifier.isStatic(f.getModifiers()) && f.getType() == String.class)
                .toList();

        assertThat(constants).hasSize(15);
        assertThat(constants).allSatisfy(field -> {
            field.setAccessible(true);
            var value = (String) field.get(null);
            assertThat(value).matches("[A-Z][A-Z_]*");
            assertThat(value).isEqualTo(field.getName());
        });
    }

    @Test
    void theClassCannotBeInstantiated() throws Exception {
        var constructor = SchemaDriftScanReason.class.getDeclaredConstructor();
        assertThat(Modifier.isPrivate(constructor.getModifiers())).isTrue();
        constructor.setAccessible(true);
        assertThat(constructor.newInstance()).isNotNull();
    }

    @Test
    void onlyRealFailuresSurfaceOnThePipeline() {
        assertThat(SchemaDriftScanReason.isFailure(SchemaDriftScanReason.TARGET_INTROSPECTION_FAILED + ": x")).isTrue();
        assertThat(SchemaDriftScanReason.isFailure(SchemaDriftScanReason.SCAN_FAILED + ": x")).isTrue();
        assertThat(SchemaDriftScanReason.isFailure(SchemaDriftScanReason.BASELINE_INTROSPECTION_FAILED)).isTrue();

        // Configuration states are recorded on the scan row but are not failures of the run.
        assertThat(SchemaDriftScanReason.isFailure(null)).isFalse();
        assertThat(SchemaDriftScanReason.isFailure(SchemaDriftScanReason.ENGINE_NOT_APPLICABLE)).isFalse();
        assertThat(SchemaDriftScanReason.isFailure(SchemaDriftScanReason.BASELINE_SNAPSHOT_MISSING)).isFalse();
        assertThat(SchemaDriftScanReason.isFailure(SchemaDriftScanReason.FK_COMPARISON_SUPPRESSED)).isFalse();
    }
}
