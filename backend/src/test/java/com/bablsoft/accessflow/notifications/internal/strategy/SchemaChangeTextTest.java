package com.bablsoft.accessflow.notifications.internal.strategy;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SchemaChangeTextTest {

    @Test
    void leavesShortAndNullErrorsAlone() {
        assertThat(SchemaChangeText.truncate(null)).isNull();
        assertThat(SchemaChangeText.truncate("boom")).isEqualTo("boom");
        var exact = "x".repeat(SchemaChangeText.ERROR_MAX_LENGTH);
        assertThat(SchemaChangeText.truncate(exact)).isEqualTo(exact);
    }

    @Test
    void boundsALongErrorWithAnEllipsis() {
        var truncated = SchemaChangeText.truncate("x".repeat(SchemaChangeText.ERROR_MAX_LENGTH + 10));
        assertThat(truncated).hasSize(SchemaChangeText.ERROR_MAX_LENGTH).endsWith("…");
    }
}
