package com.bablsoft.accessflow.core.api;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class MaskingStrategyParamsValidatorTest {

    private static String violationKey(MaskingStrategy strategy, Map<String, String> params) {
        return MaskingStrategyParamsValidator.validate(strategy, params)
                .map(MaskingStrategyParamsValidator.Violation::messageKey)
                .orElse(null);
    }

    @Test
    void nullStrategyAndParameterlessStrategiesPass() {
        assertThat(MaskingStrategyParamsValidator.validate(null, Map.of())).isEmpty();
        assertThat(violationKey(MaskingStrategy.FULL, null)).isNull();
        assertThat(violationKey(MaskingStrategy.NULLIFY, Map.of())).isNull();
        assertThat(violationKey(MaskingStrategy.EMAIL, Map.of())).isNull();
        assertThat(violationKey(MaskingStrategy.FORMAT_PRESERVING, Map.of())).isNull();
        assertThat(violationKey(MaskingStrategy.HASH, Map.of("salt", "pepper"))).isNull();
    }

    @Test
    void rejectsParamsTheStrategyDoesNotAccept() {
        var violation = MaskingStrategyParamsValidator.validate(MaskingStrategy.FULL,
                Map.of("visible_suffix", "4")).orElseThrow();
        assertThat(violation.messageKey()).isEqualTo("error.masking_params.unknown_param");
        assertThat(violation.argsArray()).containsExactly("visible_suffix", "FULL");
        assertThat(violationKey(MaskingStrategy.KEEP_FIRST, Map.of("visible_suffix", "4")))
                .isEqualTo("error.masking_params.unknown_param");
    }

    @Test
    void visibleLengthsMustBeInRange() {
        assertThat(violationKey(MaskingStrategy.PARTIAL, Map.of())).isNull();
        assertThat(violationKey(MaskingStrategy.PARTIAL, Map.of("visible_suffix", " "))).isNull();
        assertThat(violationKey(MaskingStrategy.PARTIAL, Map.of("visible_suffix", "256"))).isNull();
        assertThat(violationKey(MaskingStrategy.PARTIAL, Map.of("visible_suffix", "0")))
                .isEqualTo("error.masking_params.visible_suffix_invalid");
        assertThat(violationKey(MaskingStrategy.PARTIAL, Map.of("visible_suffix", "abc")))
                .isEqualTo("error.masking_params.visible_suffix_invalid");
        assertThat(violationKey(MaskingStrategy.KEEP_FIRST, Map.of("visible_prefix", "4"))).isNull();
        assertThat(violationKey(MaskingStrategy.KEEP_FIRST, Map.of("visible_prefix", "257")))
                .isEqualTo("error.masking_params.visible_prefix_invalid");
    }

    @Test
    void constantRequiresBoundedReplacement() {
        assertThat(violationKey(MaskingStrategy.CONSTANT, Map.of("replacement", "REDACTED"))).isNull();
        assertThat(violationKey(MaskingStrategy.CONSTANT, Map.of()))
                .isEqualTo("error.masking_params.constant_replacement_invalid");
        assertThat(violationKey(MaskingStrategy.CONSTANT, Map.of("replacement", "")))
                .isEqualTo("error.masking_params.constant_replacement_invalid");
        assertThat(violationKey(MaskingStrategy.CONSTANT, Map.of("replacement", "x".repeat(257))))
                .isEqualTo("error.masking_params.constant_replacement_invalid");
    }

    @Test
    void regexRequiresCompilablePatternAndReplacement() {
        assertThat(violationKey(MaskingStrategy.REGEX_REPLACE,
                Map.of("pattern", "^(\\d{4})\\d+$", "replacement", "$1******"))).isNull();
        assertThat(violationKey(MaskingStrategy.REGEX_REPLACE,
                Map.of("pattern", "\\d", "replacement", ""))).isNull();
        assertThat(violationKey(MaskingStrategy.REGEX_REPLACE, Map.of("replacement", "x")))
                .isEqualTo("error.masking_params.pattern_required");
        assertThat(violationKey(MaskingStrategy.REGEX_REPLACE,
                Map.of("pattern", "a".repeat(513), "replacement", "x")))
                .isEqualTo("error.masking_params.pattern_required");
        var invalid = MaskingStrategyParamsValidator.validate(MaskingStrategy.REGEX_REPLACE,
                Map.of("pattern", "(unclosed", "replacement", "x")).orElseThrow();
        assertThat(invalid.messageKey()).isEqualTo("error.masking_params.pattern_invalid");
        assertThat(invalid.args()).hasSize(1);
        assertThat(violationKey(MaskingStrategy.REGEX_REPLACE, Map.of("pattern", "a")))
                .isEqualTo("error.masking_params.regex_replacement_invalid");
        assertThat(violationKey(MaskingStrategy.REGEX_REPLACE,
                Map.of("pattern", "a", "replacement", "x".repeat(257))))
                .isEqualTo("error.masking_params.regex_replacement_invalid");
    }

    @Test
    void replacementGroupReferencesMustExist() {
        var pattern = Pattern.compile("(?<area>\\d{3})(\\d+)");
        assertThat(MaskingStrategyParamsValidator.replacementReferences(pattern, "$1-$2 ${area} \\$ lit"))
                .isEmpty();
        // "$21" with two groups resolves to group 2 followed by a literal "1", as Matcher does.
        assertThat(MaskingStrategyParamsValidator.replacementReferences(pattern, "$21")).isEmpty();
        assertThat(MaskingStrategyParamsValidator.replacementReferences(pattern, "$3").orElseThrow()
                .messageKey()).isEqualTo("error.masking_params.replacement_group_invalid");
        assertThat(MaskingStrategyParamsValidator.replacementReferences(pattern, "${nope}").orElseThrow()
                .messageKey()).isEqualTo("error.masking_params.replacement_group_invalid");
        for (var bad : new String[] {"$", "trailing\\", "$x", "${open"}) {
            assertThat(MaskingStrategyParamsValidator.replacementReferences(pattern, bad).orElseThrow()
                    .messageKey()).as(bad).isEqualTo("error.masking_params.replacement_syntax_invalid");
        }
    }

    @Test
    void numericBucketNeedsExactlyOneValidMode() {
        assertThat(violationKey(MaskingStrategy.NUMERIC_BUCKET, Map.of("bucket_size", "10000"))).isNull();
        assertThat(violationKey(MaskingStrategy.NUMERIC_BUCKET, Map.of("boundaries", "18,30,65"))).isNull();
        assertThat(violationKey(MaskingStrategy.NUMERIC_BUCKET, Map.of()))
                .isEqualTo("error.masking_params.bucket_mode_required");
        assertThat(violationKey(MaskingStrategy.NUMERIC_BUCKET,
                Map.of("bucket_size", "10", "boundaries", "1,2")))
                .isEqualTo("error.masking_params.bucket_mode_required");
        assertThat(violationKey(MaskingStrategy.NUMERIC_BUCKET, Map.of("bucket_size", "0")))
                .isEqualTo("error.masking_params.bucket_size_invalid");
        assertThat(violationKey(MaskingStrategy.NUMERIC_BUCKET, Map.of("bucket_size", "ten")))
                .isEqualTo("error.masking_params.bucket_size_invalid");
        assertThat(violationKey(MaskingStrategy.NUMERIC_BUCKET, Map.of("boundaries", "30,18")))
                .isEqualTo("error.masking_params.boundaries_invalid");
        assertThat(violationKey(MaskingStrategy.NUMERIC_BUCKET, Map.of("boundaries", "1,,2")))
                .isEqualTo("error.masking_params.boundaries_invalid");
        var many = new StringBuilder();
        for (int i = 0; i <= MaskingStrategyParamsValidator.MAX_BOUNDARIES; i++) {
            many.append(i == 0 ? "" : ",").append(i);
        }
        assertThat(violationKey(MaskingStrategy.NUMERIC_BUCKET, Map.of("boundaries", many.toString())))
                .isEqualTo("error.masking_params.boundaries_invalid");
    }

    @Test
    void dateGeneralizeRequiresKnownPrecision() {
        assertThat(violationKey(MaskingStrategy.DATE_GENERALIZE, Map.of("precision", "YEAR"))).isNull();
        assertThat(violationKey(MaskingStrategy.DATE_GENERALIZE, Map.of("precision", "quarter"))).isNull();
        assertThat(violationKey(MaskingStrategy.DATE_GENERALIZE, Map.of("precision", "DAY")))
                .isEqualTo("error.masking_params.precision_invalid");
        assertThat(violationKey(MaskingStrategy.DATE_GENERALIZE, Map.of()))
                .isEqualTo("error.masking_params.precision_invalid");
    }

    @Test
    void violationCopiesArgs() {
        var args = new java.util.ArrayList<Object>();
        args.add("a");
        var violation = new MaskingStrategyParamsValidator.Violation("k", args);
        args.add("b");
        assertThat(violation.args()).containsExactly("a");
        assertThat(new MaskingStrategyParamsValidator.Violation("k", null).args()).isEmpty();
        var withNullValue = new HashMap<String, String>();
        withNullValue.put("replacement", null);
        assertThat(violationKey(MaskingStrategy.CONSTANT, withNullValue))
                .isEqualTo("error.masking_params.constant_replacement_invalid");
    }
}
