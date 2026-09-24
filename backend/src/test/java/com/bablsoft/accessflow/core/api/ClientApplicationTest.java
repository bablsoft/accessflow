package com.bablsoft.accessflow.core.api;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class ClientApplicationTest {

    @Test
    void onlyAnApiKeySourceIsTrusted() {
        assertThat(new ClientApplication("a", ApplicationNameSource.API_KEY).trusted()).isTrue();
        assertThat(new ClientApplication("a", ApplicationNameSource.HEADER).trusted()).isFalse();
    }

    @Test
    void requiresBothFields() {
        assertThatNullPointerException().isThrownBy(() -> new ClientApplication(null, ApplicationNameSource.HEADER));
        assertThatNullPointerException().isThrownBy(() -> new ClientApplication("a", null));
    }
}
