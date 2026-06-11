package com.bablsoft.accessflow.ai.internal.web;

import com.bablsoft.accessflow.core.api.PgVectorAvailability;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AdminRagCapabilitiesControllerTest {

    @Test
    void reportsPgVectorAvailable() {
        var availability = mock(PgVectorAvailability.class);
        when(availability.isAvailable()).thenReturn(true);

        assertThat(new AdminRagCapabilitiesController(availability).capabilities().pgvectorAvailable())
                .isTrue();
    }

    @Test
    void reportsPgVectorUnavailable() {
        var availability = mock(PgVectorAvailability.class);
        when(availability.isAvailable()).thenReturn(false);

        assertThat(new AdminRagCapabilitiesController(availability).capabilities().pgvectorAvailable())
                .isFalse();
    }
}
