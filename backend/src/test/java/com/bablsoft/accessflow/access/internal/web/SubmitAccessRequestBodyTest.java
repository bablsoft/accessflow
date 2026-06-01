package com.bablsoft.accessflow.access.internal.web;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SubmitAccessRequestBodyTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void init() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void close() {
        factory.close();
    }

    private SubmitAccessRequestBody valid() {
        return new SubmitAccessRequestBody(UUID.randomUUID(), true, false, false,
                List.of("public"), null, "PT4H", "need access");
    }

    @Test
    void validBodyHasNoViolations() {
        assertThat(validator.validate(valid())).isEmpty();
    }

    @Test
    void missingDatasourceIdIsRejected() {
        var body = new SubmitAccessRequestBody(null, true, false, false, null, null, "PT4H", "j");
        assertThat(validator.validate(body))
                .anyMatch(v -> v.getPropertyPath().toString().equals("datasourceId"));
    }

    @Test
    void blankDurationIsRejected() {
        var body = new SubmitAccessRequestBody(UUID.randomUUID(), true, false, false, null, null,
                "  ", "j");
        assertThat(validator.validate(body))
                .anyMatch(v -> v.getPropertyPath().toString().equals("requestedDuration"));
    }

    @Test
    void nonIso8601DurationIsRejected() {
        var body = new SubmitAccessRequestBody(UUID.randomUUID(), true, false, false, null, null,
                "4 hours", "j");
        assertThat(validator.validate(body))
                .anyMatch(v -> v.getPropertyPath().toString().equals("requestedDuration"));
    }

    @Test
    void noCapabilityIsRejected() {
        var body = new SubmitAccessRequestBody(UUID.randomUUID(), false, false, false, null, null,
                "PT4H", "j");
        assertThat(validator.validate(body)).isNotEmpty();
    }

    @Test
    void blankJustificationIsRejected() {
        var body = new SubmitAccessRequestBody(UUID.randomUUID(), true, false, false, null, null,
                "PT4H", " ");
        assertThat(validator.validate(body))
                .anyMatch(v -> v.getPropertyPath().toString().equals("justification"));
    }
}
