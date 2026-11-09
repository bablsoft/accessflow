package com.bablsoft.accessflow.apigov.internal.web;

import com.bablsoft.accessflow.apigov.api.ApiAuthMethod;
import com.bablsoft.accessflow.apigov.api.ApiProtocol;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** Bean Validation bounds of the #971 rate-limit and budget fields on the connector requests. */
class ApiConnectorLimitsRequestValidationTest {

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
    void createRejectsNegativeLimitsAndShareAboveHundred() {
        var violations = paths(validator.validate(create(-1, -1, 101, -1L, -1L)));

        assertThat(violations).containsExactlyInAnyOrder("callsPerMinute", "callsPerDay",
                "perUserSharePercent", "costPerCallMicros", "monthlyBudgetMicros");
    }

    @Test
    void createRejectsNegativeShare() {
        assertThat(paths(validator.validate(create(null, null, -1, null, null))))
                .containsExactly("perUserSharePercent");
    }

    @Test
    void createAcceptsZeroNullAndInRangeValues() {
        assertThat(validator.validate(create(0, 0, 0, 0L, 0L))).isEmpty();
        assertThat(validator.validate(create(null, null, null, null, null))).isEmpty();
        assertThat(validator.validate(create(60, 1_000, 100, 1_500L, 9_000_000L))).isEmpty();
    }

    @Test
    void updateRejectsNegativeLimitsAndShareAboveHundred() {
        var violations = paths(validator.validate(update(-1, -1, 101, -1L, -1L)));

        assertThat(violations).containsExactlyInAnyOrder("callsPerMinute", "callsPerDay",
                "perUserSharePercent", "costPerCallMicros", "monthlyBudgetMicros");
    }

    @Test
    void updateAcceptsZeroNullAndInRangeValues() {
        assertThat(validator.validate(update(0, 0, 0, 0L, 0L))).isEmpty();
        assertThat(validator.validate(update(null, null, null, null, null))).isEmpty();
        assertThat(validator.validate(update(1, 1, 1, 1L, 1L))).isEmpty();
    }

    @Test
    void toCommandCarriesTheLimits() {
        var createCommand = create(60, 1_000, 25, 1_500L, 9_000_000L).toCommand(null);
        assertThat(createCommand.callsPerMinute()).isEqualTo(60);
        assertThat(createCommand.callsPerDay()).isEqualTo(1_000);
        assertThat(createCommand.perUserSharePercent()).isEqualTo(25);
        assertThat(createCommand.costPerCallMicros()).isEqualTo(1_500L);
        assertThat(createCommand.monthlyBudgetMicros()).isEqualTo(9_000_000L);

        var updateCommand = update(0, 5, null, 2L, null).toCommand();
        assertThat(updateCommand.callsPerMinute()).isZero();
        assertThat(updateCommand.callsPerDay()).isEqualTo(5);
        assertThat(updateCommand.perUserSharePercent()).isNull();
        assertThat(updateCommand.costPerCallMicros()).isEqualTo(2L);
        assertThat(updateCommand.monthlyBudgetMicros()).isNull();
    }

    private static Set<String> paths(Set<? extends ConstraintViolation<?>> violations) {
        return violations.stream().map(v -> v.getPropertyPath().toString()).collect(Collectors.toSet());
    }

    private static CreateApiConnectorRequest create(Integer callsPerMinute, Integer callsPerDay, Integer share,
                                                    Long cost, Long budget) {
        return new CreateApiConnectorRequest("Stripe", ApiProtocol.REST, "https://api.stripe.com",
                Map.of(), null, 5000, true, ApiAuthMethod.NONE, null,
                null, null, null, null, null, null, null, null, null, null,
                null, true, null, false, false, true, 2048L, null,
                callsPerMinute, callsPerDay, share, cost, budget);
    }

    private static UpdateApiConnectorRequest update(Integer callsPerMinute, Integer callsPerDay, Integer share,
                                                    Long cost, Long budget) {
        return new UpdateApiConnectorRequest(null,
                null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                callsPerMinute, callsPerDay, share, cost, budget);
    }
}
