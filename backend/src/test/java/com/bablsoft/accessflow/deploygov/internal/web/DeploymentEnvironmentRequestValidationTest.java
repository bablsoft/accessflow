package com.bablsoft.accessflow.deploygov.internal.web;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/** The environment tag caps (#741) mirror the query-template ones: at most 10 tags, 32 chars each. */
class DeploymentEnvironmentRequestValidationTest {

    private static jakarta.validation.ValidatorFactory factory;
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

    private static List<String> tags(int count) {
        return IntStream.range(0, count).mapToObj(i -> "tag-" + i).toList();
    }

    @Test
    void createRejectsMoreThanTenTags() {
        var request = new CreateDeploymentEnvironmentRequest("production", null, null, null, null,
                null, tags(11));

        assertThat(validator.validate(request))
                .extracting(v -> v.getPropertyPath().toString())
                .contains("tags");
    }

    @Test
    void createRejectsATagLongerThan32Characters() {
        var request = new CreateDeploymentEnvironmentRequest("production", null, null, null, null,
                null, List.of("x".repeat(33)));

        assertThat(validator.validate(request))
                .extracting(v -> v.getPropertyPath().toString())
                .anyMatch(path -> path.startsWith("tags"));
    }

    @Test
    void createAcceptsTenTagsOfMaximumLength() {
        var request = new CreateDeploymentEnvironmentRequest("production", null, null, null, null,
                null, IntStream.range(0, 10).mapToObj(i -> String.valueOf((char) ('a' + i)).repeat(32))
                        .toList());

        assertThat(validator.validate(request)).isEmpty();
    }

    @Test
    void createAcceptsAbsentTags() {
        var request = new CreateDeploymentEnvironmentRequest("production", null, null, null, null,
                null, null);

        assertThat(validator.validate(request)).isEmpty();
    }

    @Test
    void updateRejectsMoreThanTenTags() {
        var request = new UpdateDeploymentEnvironmentRequest(null, null, null, null, null, null,
                null, null, tags(11));

        assertThat(validator.validate(request))
                .extracting(v -> v.getPropertyPath().toString())
                .contains("tags");
    }

    @Test
    void updateRejectsATagLongerThan32Characters() {
        var request = new UpdateDeploymentEnvironmentRequest(null, null, null, null, null, null,
                null, null, List.of("x".repeat(33)));

        assertThat(validator.validate(request))
                .extracting(v -> v.getPropertyPath().toString())
                .anyMatch(path -> path.startsWith("tags"));
    }

    @Test
    void updateAcceptsAnEmptyTagListAsAnExplicitClear() {
        var request = new UpdateDeploymentEnvironmentRequest(null, null, null, null, null, null,
                null, null, List.of());

        assertThat(validator.validate(request)).isEmpty();
    }
}
