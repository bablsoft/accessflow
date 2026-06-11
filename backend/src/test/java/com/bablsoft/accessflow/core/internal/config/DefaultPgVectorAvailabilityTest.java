package com.bablsoft.accessflow.core.internal.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultPgVectorAvailabilityTest {

    @Test
    void defaultsToUnavailable() {
        assertThat(new DefaultPgVectorAvailability().isAvailable()).isFalse();
    }

    @Test
    void reflectsSetValue() {
        var availability = new DefaultPgVectorAvailability();

        availability.set(true);
        assertThat(availability.isAvailable()).isTrue();

        availability.set(false);
        assertThat(availability.isAvailable()).isFalse();
    }
}
