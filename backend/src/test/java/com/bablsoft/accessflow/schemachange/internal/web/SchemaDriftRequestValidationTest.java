package com.bablsoft.accessflow.schemachange.internal.web;

import com.bablsoft.accessflow.schemachange.api.SchemaDriftBaseline;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SchemaDriftRequestValidationTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        factory.close();
    }

    private static <T> java.util.Set<String> messages(T request) {
        return validator.validate(request).stream()
                .map(ConstraintViolation::getMessageTemplate)
                .collect(java.util.stream.Collectors.toSet());
    }

    @Test
    void aCompleteConfigIsValid() {
        assertThat(messages(new UpsertSchemaDriftConfigRequest(true, SchemaDriftBaseline.PREVIOUS_ENVIRONMENT,
                null, 24))).isEmpty();
    }

    /** Boxed so an absent field is a 400 here, rather than a Jackson 3 primitive failure and a 500. */
    @Test
    void everyRequiredFieldIsReportedWhenAbsent() {
        assertThat(messages(new UpsertSchemaDriftConfigRequest(null, null, null, null))).containsExactlyInAnyOrder(
                "{validation.schema_drift_config.enabled.required}",
                "{validation.schema_drift_config.baseline.required}",
                "{validation.schema_drift_config.scan_interval_hours.required}");
    }

    @Test
    void theScanIntervalIsBoundedBothWays() {
        assertThat(messages(new UpsertSchemaDriftConfigRequest(true, SchemaDriftBaseline.PREVIOUS_ENVIRONMENT,
                null, 0))).containsExactly("{validation.schema_drift_config.scan_interval_hours.range}");
        assertThat(messages(new UpsertSchemaDriftConfigRequest(true, SchemaDriftBaseline.PREVIOUS_ENVIRONMENT,
                null, 721))).containsExactly("{validation.schema_drift_config.scan_interval_hours.range}");
        assertThat(messages(new UpsertSchemaDriftConfigRequest(true, SchemaDriftBaseline.PREVIOUS_ENVIRONMENT,
                null, 720))).isEmpty();
    }

    @Test
    void aScanRequestNeedsAnEnvironment() {
        assertThat(messages(new RequestSchemaDriftScanRequest(null)))
                .containsExactly("{validation.schema_drift_scan.environment_id.required}");
        assertThat(messages(new RequestSchemaDriftScanRequest(UUID.randomUUID()))).isEmpty();
    }
}
