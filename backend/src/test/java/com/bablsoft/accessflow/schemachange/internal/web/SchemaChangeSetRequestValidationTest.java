package com.bablsoft.accessflow.schemachange.internal.web;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SchemaChangeSetRequestValidationTest {

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

    @Test
    void createRequiresPipelineAndNameWithinBounds() {
        var request = new CreateSchemaChangeSetRequest(null, "ab", "x".repeat(2001), null);

        assertThat(validator.validate(request)).extracting(v -> v.getPropertyPath().toString())
                .containsExactlyInAnyOrder("pipelineId", "name", "description");
    }

    @Test
    void createRejectsABlankName() {
        assertThat(validator.validate(new CreateSchemaChangeSetRequest(UUID.randomUUID(), "   ", null, null)))
                .extracting(v -> v.getPropertyPath().toString()).containsExactly("name");
    }

    @Test
    void createCascadesIntoStatements() {
        var request = new CreateSchemaChangeSetRequest(UUID.randomUUID(), "change-set", null, List.of(
                new SchemaChangeSetStatementRequest("CREATE TABLE t (id INT)"),
                new SchemaChangeSetStatementRequest("  "),
                new SchemaChangeSetStatementRequest("x".repeat(100_001))));

        assertThat(validator.validate(request)).extracting(v -> v.getPropertyPath().toString())
                .containsExactlyInAnyOrder("statements[1].sqlText", "statements[2].sqlText");
    }

    @Test
    void createAcceptsAValidRequestWithoutStatements() {
        assertThat(validator.validate(new CreateSchemaChangeSetRequest(UUID.randomUUID(), "change-set", "d", null)))
                .isEmpty();
    }

    @Test
    void replaceRequiresTheListButAllowsItEmpty() {
        assertThat(validator.validate(new ReplaceSchemaChangeSetStatementsRequest(null)))
                .extracting(v -> v.getPropertyPath().toString()).containsExactly("statements");
        assertThat(validator.validate(new ReplaceSchemaChangeSetStatementsRequest(List.of()))).isEmpty();
        assertThat(validator.validate(new ReplaceSchemaChangeSetStatementsRequest(
                List.of(new SchemaChangeSetStatementRequest("")))))
                .extracting(v -> v.getPropertyPath().toString()).containsExactly("statements[0].sqlText");
    }

    @Test
    void updateBoundsNameAndDescriptionButAllowsNulls() {
        assertThat(validator.validate(new UpdateSchemaChangeSetRequest(null, null, null))).isEmpty();
        assertThat(validator.validate(new UpdateSchemaChangeSetRequest("ab", "x".repeat(2001), null)))
                .extracting(v -> v.getPropertyPath().toString()).containsExactlyInAnyOrder("name", "description");
    }
}
