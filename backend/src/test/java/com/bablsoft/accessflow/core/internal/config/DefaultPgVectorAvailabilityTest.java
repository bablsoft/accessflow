package com.bablsoft.accessflow.core.internal.config;

import com.bablsoft.accessflow.core.api.PgVectorStatus;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultPgVectorAvailabilityTest {

    @Test
    void defaultsToUnavailable() {
        var availability = new DefaultPgVectorAvailability();

        assertThat(availability.isAvailable()).isFalse();
        assertThat(availability.status()).isEqualTo(PgVectorStatus.EXTENSION_MISSING);
    }

    @Test
    void reflectsSetValue() {
        var availability = new DefaultPgVectorAvailability();

        availability.set(PgVectorStatus.AVAILABLE);
        assertThat(availability.isAvailable()).isTrue();
        assertThat(availability.status()).isEqualTo(PgVectorStatus.AVAILABLE);

        availability.set(PgVectorStatus.DISABLED);
        assertThat(availability.isAvailable()).isFalse();
        assertThat(availability.status()).isEqualTo(PgVectorStatus.DISABLED);

        availability.set(PgVectorStatus.EXTENSION_MISSING);
        assertThat(availability.isAvailable()).isFalse();
        assertThat(availability.status()).isEqualTo(PgVectorStatus.EXTENSION_MISSING);
    }
}
