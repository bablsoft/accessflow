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
        return new SubmitAccessRequestBody(UUID.randomUUID(), null, true, false, false, false,
                List.of("public"), null, null, "PT4H", "need access");
    }

    private SubmitAccessRequestBody validConnector() {
        return new SubmitAccessRequestBody(null, UUID.randomUUID(), true, false, false, false,
                null, null, List.of("getPets"), "PT4H", "need access");
    }

    @Test
    void validDatasourceBodyHasNoViolations() {
        assertThat(validator.validate(valid())).isEmpty();
    }

    @Test
    void validConnectorBodyHasNoViolations() {
        assertThat(validator.validate(validConnector())).isEmpty();
    }

    @Test
    void missingBothResourcesIsRejected() {
        var body = new SubmitAccessRequestBody(null, null, true, false, false, false, null, null,
                null, "PT4H", "j");
        assertThat(validator.validate(body)).isNotEmpty();
    }

    @Test
    void bothResourcesIsRejected() {
        var body = new SubmitAccessRequestBody(UUID.randomUUID(), UUID.randomUUID(), true, false,
                false, false, null, null, null, "PT4H", "j");
        assertThat(validator.validate(body)).isNotEmpty();
    }

    @Test
    void connectorWithDdlIsRejected() {
        var body = new SubmitAccessRequestBody(null, UUID.randomUUID(), true, false, true, false,
                null, null, null, "PT4H", "j");
        assertThat(validator.validate(body)).isNotEmpty();
    }

    @Test
    void connectorWithPreApproveIsRejected() {
        var body = new SubmitAccessRequestBody(null, UUID.randomUUID(), true, false, false, true,
                null, null, null, "PT4H", "j");
        assertThat(validator.validate(body)).isNotEmpty();
    }

    @Test
    void connectorWithSchemaScopeIsRejected() {
        var body = new SubmitAccessRequestBody(null, UUID.randomUUID(), true, false, false, false,
                List.of("public"), null, null, "PT4H", "j");
        assertThat(validator.validate(body)).isNotEmpty();
    }

    @Test
    void datasourceWithOperationsIsRejected() {
        var body = new SubmitAccessRequestBody(UUID.randomUUID(), null, true, false, false, false,
                null, null, List.of("getPets"), "PT4H", "j");
        assertThat(validator.validate(body)).isNotEmpty();
    }

    @Test
    void blankOperationEntryIsRejected() {
        var body = new SubmitAccessRequestBody(null, UUID.randomUUID(), true, false, false, false,
                null, null, List.of(" "), "PT4H", "j");
        assertThat(validator.validate(body)).isNotEmpty();
    }

    @Test
    void blankDurationIsRejected() {
        var body = new SubmitAccessRequestBody(UUID.randomUUID(), null, true, false, false, false,
                null, null, null, "  ", "j");
        assertThat(validator.validate(body))
                .anyMatch(v -> v.getPropertyPath().toString().equals("requestedDuration"));
    }

    @Test
    void nonIso8601DurationIsRejected() {
        var body = new SubmitAccessRequestBody(UUID.randomUUID(), null, true, false, false, false,
                null, null, null, "4 hours", "j");
        assertThat(validator.validate(body))
                .anyMatch(v -> v.getPropertyPath().toString().equals("requestedDuration"));
    }

    @Test
    void noCapabilityIsRejected() {
        var body = new SubmitAccessRequestBody(UUID.randomUUID(), null, false, false, false, false,
                null, null, null, "PT4H", "j");
        assertThat(validator.validate(body)).isNotEmpty();
    }

    @Test
    void blankJustificationIsRejected() {
        var body = new SubmitAccessRequestBody(UUID.randomUUID(), null, true, false, false, false,
                null, null, null, "PT4H", " ");
        assertThat(validator.validate(body))
                .anyMatch(v -> v.getPropertyPath().toString().equals("justification"));
    }
}
