package com.bablsoft.accessflow.access.internal.web;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AtLeastOneCapabilityValidatorTest {

    private final AtLeastOneCapabilityValidator validator = new AtLeastOneCapabilityValidator();

    private SubmitAccessRequestBody body(boolean r, boolean w, boolean d) {
        return new SubmitAccessRequestBody(UUID.randomUUID(), r, w, d, false, null, null, "PT4H", "j");
    }

    @Test
    void nullIsValid() {
        assertThat(validator.isValid(null, null)).isTrue();
    }

    @Test
    void anyCapabilityIsValid() {
        assertThat(validator.isValid(body(true, false, false), null)).isTrue();
        assertThat(validator.isValid(body(false, true, false), null)).isTrue();
        assertThat(validator.isValid(body(false, false, true), null)).isTrue();
    }

    @Test
    void noCapabilityIsInvalid() {
        assertThat(validator.isValid(body(false, false, false), null)).isFalse();
    }
}
