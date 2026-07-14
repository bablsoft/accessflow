package com.bablsoft.accessflow.access.internal.web;

import jakarta.validation.ConstraintValidatorContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

@ExtendWith(MockitoExtension.class)
class ExactlyOneResourceValidatorTest {

    private final ExactlyOneResourceValidator validator = new ExactlyOneResourceValidator();

    @Mock
    private ConstraintValidatorContext context;

    @BeforeEach
    void stubContext() {
        lenient().when(context.buildConstraintViolationWithTemplate(anyString()))
                .thenReturn(mock(
                        ConstraintValidatorContext.ConstraintViolationBuilder.class));
    }

    private static SubmitAccessRequestBody body(UUID datasourceId, UUID connectorId,
                                                Boolean canDdl, Boolean preApprove,
                                                List<String> schemas, List<String> tables,
                                                List<String> operations) {
        return new SubmitAccessRequestBody(datasourceId, connectorId, true, false, canDdl,
                preApprove, schemas, tables, operations, "PT4H", "j");
    }

    @Test
    void nullBodyIsValid() {
        assertThat(validator.isValid(null, context)).isTrue();
    }

    @Test
    void datasourceOnlyIsValid() {
        var body = body(UUID.randomUUID(), null, true, true, List.of("public"), List.of("t"), null);
        assertThat(validator.isValid(body, context)).isTrue();
    }

    @Test
    void connectorOnlyIsValid() {
        var body = body(null, UUID.randomUUID(), false, false, null, null, List.of("getPets"));
        assertThat(validator.isValid(body, context)).isTrue();
    }

    @Test
    void neitherResourceIsInvalid() {
        assertThat(validator.isValid(body(null, null, false, false, null, null, null), context))
                .isFalse();
    }

    @Test
    void bothResourcesIsInvalid() {
        var body = body(UUID.randomUUID(), UUID.randomUUID(), false, false, null, null, null);
        assertThat(validator.isValid(body, context)).isFalse();
    }

    @Test
    void connectorWithDdlIsInvalid() {
        var body = body(null, UUID.randomUUID(), true, false, null, null, null);
        assertThat(validator.isValid(body, context)).isFalse();
    }

    @Test
    void connectorWithPreApproveIsInvalid() {
        var body = body(null, UUID.randomUUID(), false, true, null, null, null);
        assertThat(validator.isValid(body, context)).isFalse();
    }

    @Test
    void connectorWithSchemasIsInvalid() {
        var body = body(null, UUID.randomUUID(), false, false, List.of("public"), null, null);
        assertThat(validator.isValid(body, context)).isFalse();
    }

    @Test
    void connectorWithTablesIsInvalid() {
        var body = body(null, UUID.randomUUID(), false, false, null, List.of("t"), null);
        assertThat(validator.isValid(body, context)).isFalse();
    }

    @Test
    void connectorWithEmptySchemaListsIsValid() {
        var body = body(null, UUID.randomUUID(), false, false, List.of(), List.of(), null);
        assertThat(validator.isValid(body, context)).isTrue();
    }

    @Test
    void datasourceWithOperationsIsInvalid() {
        var body = body(UUID.randomUUID(), null, false, false, null, null, List.of("getPets"));
        assertThat(validator.isValid(body, context)).isFalse();
    }

    @Test
    void connectorWithNullDdlAndPreApproveIsValid() {
        var body = new SubmitAccessRequestBody(null, UUID.randomUUID(), true, false, null, null,
                null, null, null, "PT4H", "j");
        assertThat(validator.isValid(body, context)).isTrue();
    }
}
