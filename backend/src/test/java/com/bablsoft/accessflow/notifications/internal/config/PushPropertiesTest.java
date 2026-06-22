package com.bablsoft.accessflow.notifications.internal.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PushPropertiesTest {

    @Test
    void defaultsSubjectWhenBlank() {
        assertThat(new PushProperties(null, null, null).subject())
                .isEqualTo(PushProperties.DEFAULT_SUBJECT);
        assertThat(new PushProperties(null, null, "  ").subject())
                .isEqualTo(PushProperties.DEFAULT_SUBJECT);
    }

    @Test
    void keepsExplicitSubject() {
        assertThat(new PushProperties(null, null, "mailto:ops@acme.test").subject())
                .isEqualTo("mailto:ops@acme.test");
    }

    @Test
    void hasExplicitKeyPairRequiresBothKeys() {
        assertThat(new PushProperties("pub", "priv", null).hasExplicitKeyPair()).isTrue();
        assertThat(new PushProperties("pub", null, null).hasExplicitKeyPair()).isFalse();
        assertThat(new PushProperties(null, "priv", null).hasExplicitKeyPair()).isFalse();
        assertThat(new PushProperties("", "", null).hasExplicitKeyPair()).isFalse();
    }
}
