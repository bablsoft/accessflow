package com.bablsoft.accessflow.deploygov.internal.web;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.bablsoft.accessflow.deploygov.api.VerificationCheckKind;
import com.bablsoft.accessflow.deploygov.api.VerificationComparator;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
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
                null, tags(11), null, null, null);

        assertThat(validator.validate(request))
                .extracting(v -> v.getPropertyPath().toString())
                .contains("tags");
    }

    @Test
    void createRejectsATagLongerThan32Characters() {
        var request = new CreateDeploymentEnvironmentRequest("production", null, null, null, null,
                null, List.of("x".repeat(33)), null, null, null);

        assertThat(validator.validate(request))
                .extracting(v -> v.getPropertyPath().toString())
                .anyMatch(path -> path.startsWith("tags"));
    }

    @Test
    void createAcceptsTenTagsOfMaximumLength() {
        var request = new CreateDeploymentEnvironmentRequest("production", null, null, null, null,
                null, IntStream.range(0, 10).mapToObj(i -> String.valueOf((char) ('a' + i)).repeat(32))
                        .toList(), null, null, null);

        assertThat(validator.validate(request)).isEmpty();
    }

    @Test
    void createRejectsANegativeSortOrder() {
        var request = new CreateDeploymentEnvironmentRequest("production", -1, null, null, null,
                null, null, null, null, null);

        assertThat(validator.validate(request))
                .extracting(v -> v.getPropertyPath().toString())
                .containsExactly("sortOrder");
    }

    @Test
    void createAcceptsZeroSortOrderAndADatasource() {
        var request = new CreateDeploymentEnvironmentRequest("production", 0, null, null, null,
                null, null, UUID.randomUUID(), null, null);

        assertThat(validator.validate(request)).isEmpty();
    }

    @Test
    void updateRejectsANegativeSortOrder() {
        var request = new UpdateDeploymentEnvironmentRequest(null, -5, null, null, null, null,
                null, null, null, null, null, null, null, null);

        assertThat(validator.validate(request))
                .extracting(v -> v.getPropertyPath().toString())
                .containsExactly("sortOrder");
    }

    @Test
    void changeTicketRequiredIsUnconstrainedOnBothBodies() {
        var create = new CreateDeploymentEnvironmentRequest("production", null, null, null, null,
                null, null, null, true, null);
        var update = new UpdateDeploymentEnvironmentRequest(null, null, null, null, null, null,
                null, null, null, null, null, false, null, null);

        assertThat(validator.validate(create)).isEmpty();
        assertThat(validator.validate(update)).isEmpty();
    }

    @Test
    void createAcceptsAbsentTags() {
        var request = new CreateDeploymentEnvironmentRequest("production", null, null, null, null,
                null, null, null, null, null);

        assertThat(validator.validate(request)).isEmpty();
    }

    @Test
    void updateRejectsMoreThanTenTags() {
        var request = new UpdateDeploymentEnvironmentRequest(null, null, null, null, null, null,
                null, null, tags(11), null, null, null, null, null);

        assertThat(validator.validate(request))
                .extracting(v -> v.getPropertyPath().toString())
                .contains("tags");
    }

    @Test
    void updateRejectsATagLongerThan32Characters() {
        var request = new UpdateDeploymentEnvironmentRequest(null, null, null, null, null, null,
                null, null, List.of("x".repeat(33)), null, null, null, null, null);

        assertThat(validator.validate(request))
                .extracting(v -> v.getPropertyPath().toString())
                .anyMatch(path -> path.startsWith("tags"));
    }

    @Test
    void updateAcceptsAnEmptyTagListAsAnExplicitClear() {
        var request = new UpdateDeploymentEnvironmentRequest(null, null, null, null, null, null,
                null, null, List.of(), null, null, null, null, null);

        assertThat(validator.validate(request)).isEmpty();
    }

    private static DeploymentVerificationCheckRequest http(String url) {
        return new DeploymentVerificationCheckRequest(VerificationCheckKind.HTTP_HEALTH, "health", url,
                null, null, null, null, null, null, null);
    }

    private static DeploymentVerificationCheckRequest promql(String prometheusUrl, String query,
                                                             VerificationComparator comparator,
                                                             Double threshold) {
        return new DeploymentVerificationCheckRequest(VerificationCheckKind.PROMQL_THRESHOLD,
                "error-rate", null, null, null, prometheusUrl, query, comparator, threshold, null);
    }

    private static DeploymentVerificationConfigRequest config(Integer failureThreshold,
            List<DeploymentVerificationCheckRequest> checks) {
        return new DeploymentVerificationConfigRequest(Duration.ofMinutes(15), Duration.ofMinutes(1),
                failureThreshold, checks);
    }

    private static List<String> violations(Object request) {
        return validator.validate(request).stream()
                .map(v -> v.getPropertyPath().toString())
                .toList();
    }

    @Test
    void acceptsAValidVerificationConfigOfBothKinds() {
        var request = new CreateDeploymentEnvironmentRequest("production", null, null, null, null,
                null, null, null, null, config(3, List.of(http("https://svc/healthz"),
                        promql("https://prom", "up", VerificationComparator.LT, 0.5))));

        assertThat(validator.validate(request)).isEmpty();
    }

    @Test
    void verificationRequiresWindowIntervalAndThreshold() {
        var request = new CreateDeploymentEnvironmentRequest("production", null, null, null, null,
                null, null, null, null, new DeploymentVerificationConfigRequest(null, null, null,
                        List.of(http("https://svc"))));

        assertThat(violations(request)).contains("verification.window",
                "verification.sampleInterval", "verification.failureThreshold");
    }

    @Test
    void failureThresholdMustBeBetweenOneAndTwenty() {
        assertThat(violations(config(0, List.of(http("https://svc"))))).contains("failureThreshold");
        assertThat(violations(config(21, List.of(http("https://svc"))))).contains("failureThreshold");
        assertThat(violations(config(20, List.of(http("https://svc"))))).isEmpty();
        assertThat(violations(config(1, List.of(http("https://svc"))))).isEmpty();
    }

    @Test
    void checksMustHoldOneToTenEntries() {
        assertThat(violations(config(1, List.of()))).contains("checks");
        assertThat(violations(config(1, null))).contains("checks");
        assertThat(violations(config(1, IntStream.range(0, 11).mapToObj(i -> http("https://svc"))
                .toList()))).contains("checks");
        assertThat(violations(config(1, IntStream.range(0, 10).mapToObj(i -> http("https://svc"))
                .toList()))).isEmpty();
    }

    @Test
    void checksMayNotContainNull() {
        var checks = new java.util.ArrayList<DeploymentVerificationCheckRequest>();
        checks.add(null);

        assertThat(violations(config(1, checks))).anyMatch(path -> path.startsWith("checks"));
    }

    @Test
    void checkRequiresKindAndName() {
        var check = new DeploymentVerificationCheckRequest(null, " ", "https://svc", null, null, null,
                null, null, null, null);

        assertThat(violations(check)).contains("kind", "name");
    }

    @Test
    void checkNameIsCappedAt64Characters() {
        var check = new DeploymentVerificationCheckRequest(VerificationCheckKind.HTTP_HEALTH,
                "x".repeat(65), "https://svc", null, null, null, null, null, null, null);

        assertThat(violations(check)).containsExactly("name");
    }

    @Test
    void urlsAreCappedAt2048Characters() {
        var longUrl = "https://svc/" + "x".repeat(2040);
        var check = new DeploymentVerificationCheckRequest(VerificationCheckKind.PROMQL_THRESHOLD,
                "p", longUrl, null, null, longUrl, "up", VerificationComparator.LT, 1.0, null);

        assertThat(violations(check)).contains("url", "prometheusUrl");
    }

    @Test
    void expectedStatusMustBeAnHttpStatus() {
        var low = new DeploymentVerificationCheckRequest(VerificationCheckKind.HTTP_HEALTH, "h",
                "https://svc", 99, null, null, null, null, null, null);
        var high = new DeploymentVerificationCheckRequest(VerificationCheckKind.HTTP_HEALTH, "h",
                "https://svc", 600, null, null, null, null, null, null);
        var ok = new DeploymentVerificationCheckRequest(VerificationCheckKind.HTTP_HEALTH, "h",
                "https://svc", 599, null, null, null, null, null, null);

        assertThat(violations(low)).containsExactly("expectedStatus");
        assertThat(violations(high)).containsExactly("expectedStatus");
        assertThat(violations(ok)).isEmpty();
    }

    @Test
    void bodyContainsAndQueryAreCapped() {
        var check = new DeploymentVerificationCheckRequest(VerificationCheckKind.PROMQL_THRESHOLD,
                "p", null, null, "x".repeat(1025), "https://prom", "q".repeat(4097),
                VerificationComparator.LT, 1.0, null);

        assertThat(violations(check)).contains("bodyContains", "query");
    }

    @Test
    void httpCheckNeedsAUrl() {
        assertThat(violations(http(null))).containsExactly("kindFieldsPresent");
        assertThat(violations(http(" "))).containsExactly("kindFieldsPresent");
    }

    @Test
    void promqlCheckNeedsUrlQueryComparatorAndThreshold() {
        assertThat(violations(promql(null, "up", VerificationComparator.LT, 1.0)))
                .containsExactly("kindFieldsPresent");
        assertThat(violations(promql("https://prom", " ", VerificationComparator.LT, 1.0)))
                .containsExactly("kindFieldsPresent");
        assertThat(violations(promql("https://prom", "up", null, 1.0)))
                .containsExactly("kindFieldsPresent");
        assertThat(violations(promql("https://prom", "up", VerificationComparator.LT, null)))
                .containsExactly("kindFieldsPresent");
        assertThat(violations(promql("https://prom", "up", VerificationComparator.LT, 1.0))).isEmpty();
    }

    @Test
    void updateValidatesTheNestedVerificationConfig() {
        var request = new UpdateDeploymentEnvironmentRequest(null, null, null, null, null, null, null,
                null, null, null, null, null, config(0, List.of(http(null))), null);

        assertThat(violations(request)).contains("verification.failureThreshold",
                "verification.checks[0].kindFieldsPresent");
    }
}
