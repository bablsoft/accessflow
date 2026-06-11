package com.bablsoft.accessflow.core.internal.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PgVectorPropertiesTest {

    @Test
    void defaultsToEnabledAndAutoProvisionWhenNull() {
        var props = new PgVectorProperties(null, null);

        assertThat(props.enabled()).isTrue();
        assertThat(props.autoProvision()).isTrue();
    }

    @Test
    void honoursExplicitValues() {
        var props = new PgVectorProperties(false, false);

        assertThat(props.enabled()).isFalse();
        assertThat(props.autoProvision()).isFalse();
    }
}
