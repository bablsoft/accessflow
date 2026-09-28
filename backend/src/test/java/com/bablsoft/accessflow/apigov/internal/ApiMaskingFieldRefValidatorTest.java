package com.bablsoft.accessflow.apigov.internal;

import com.bablsoft.accessflow.apigov.api.ApiMaskingMatcherType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class ApiMaskingFieldRefValidatorTest {

    @ParameterizedTest
    @ValueSource(strings = {"//ssn", "/Envelope/Body/user/ssn", "//user/@card", "//a | //b",
            "//*[local-name()='ssn']", "//user[position() = 1]/email", "//price[text()='$5']",
            "//a[@t=\"x:y\"]", "child::ssn"})
    void acceptsNodeSelectingXPath(String expression) {
        assertThat(ApiMaskingFieldRefValidator.validate(ApiMaskingMatcherType.XML_PATH, expression)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"//user[", "//ssn[@", "///", "count(//ssn)", "string(//ssn)", "//ns:ssn",
            "//ssn[$x]", "//a[@x:y]", "unknownFn(//ssn)", "//a[@t='$x']/b[$y]"})
    void rejectsInvalidOrNonNodeSetXPath(String expression) {
        assertThat(ApiMaskingFieldRefValidator.validate(ApiMaskingMatcherType.XML_PATH, expression))
                .contains(ApiMaskingFieldRefValidator.INVALID_XPATH_KEY);
    }

    @ParameterizedTest
    @ValueSource(strings = {"\\d{3}-\\d{2}-\\d{4}", "\"ssn\":\"([^\"]+)\"", "(?i)secret"})
    void acceptsCompilableRegex(String regex) {
        assertThat(ApiMaskingFieldRefValidator.validate(ApiMaskingMatcherType.REGEX, regex)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"(unclosed", "[a-z", "*leading", "\\"})
    void rejectsUncompilableRegex(String regex) {
        assertThat(ApiMaskingFieldRefValidator.validate(ApiMaskingMatcherType.REGEX, regex))
                .contains(ApiMaskingFieldRefValidator.INVALID_REGEX_KEY);
    }

    @Test
    void doesNotSyntaxCheckSchemaFieldOrJsonPath() {
        assertThat(ApiMaskingFieldRefValidator.validate(ApiMaskingMatcherType.SCHEMA_FIELD, "(")).isEmpty();
        assertThat(ApiMaskingFieldRefValidator.validate(ApiMaskingMatcherType.JSON_PATH, "user[")).isEmpty();
    }
}
