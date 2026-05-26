package com.bablsoft.accessflow.workflow.internal.web;

import com.bablsoft.accessflow.core.api.DecisionType;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class BulkReviewDecisionRequestTest {

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

    @Test
    void rejectsEmptyQueryIds() {
        var req = new BulkReviewDecisionRequest(List.of(), DecisionType.APPROVED, null);

        var violations = validator.validate(req);

        assertThat(violations).extracting(v -> v.getPropertyPath().toString())
                .contains("queryIds");
    }

    @Test
    void rejectsOversizedQueryIdsList() {
        var ids = IntStream.range(0, 101).mapToObj(i -> UUID.randomUUID()).collect(Collectors.toList());
        var req = new BulkReviewDecisionRequest(ids, DecisionType.APPROVED, null);

        var violations = validator.validate(req);

        assertThat(violations).extracting(v -> v.getPropertyPath().toString())
                .contains("queryIds");
    }

    @Test
    void rejectsMissingDecision() {
        var req = new BulkReviewDecisionRequest(List.of(UUID.randomUUID()), null, null);

        var violations = validator.validate(req);

        assertThat(violations).extracting(v -> v.getPropertyPath().toString())
                .contains("decision");
    }

    @Test
    void rejectsOversizedComment() {
        var req = new BulkReviewDecisionRequest(List.of(UUID.randomUUID()),
                DecisionType.REJECTED, "x".repeat(4001));

        var violations = validator.validate(req);

        assertThat(violations).extracting(v -> v.getPropertyPath().toString())
                .contains("comment");
    }

    @Test
    void acceptsValidApprovalRequestWithoutComment() {
        var req = new BulkReviewDecisionRequest(List.of(UUID.randomUUID()),
                DecisionType.APPROVED, null);

        var violations = validator.validate(req);

        assertThat(violations).isEmpty();
    }

    @Test
    void requiresCommentReturnsTrueOnlyForNonApproval() {
        assertThat(new BulkReviewDecisionRequest(List.of(UUID.randomUUID()),
                DecisionType.APPROVED, null).requiresComment()).isFalse();
        assertThat(new BulkReviewDecisionRequest(List.of(UUID.randomUUID()),
                DecisionType.REJECTED, null).requiresComment()).isTrue();
        assertThat(new BulkReviewDecisionRequest(List.of(UUID.randomUUID()),
                DecisionType.REQUESTED_CHANGES, null).requiresComment()).isTrue();
    }
}
