package com.bablsoft.accessflow.sqlreview.internal;

import com.bablsoft.accessflow.sqlreview.api.IllegalSqlReviewRulesetException;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticMessageSource;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

class SqlRuleParamsCodecTest {

    private final SqlRuleParamsCodec codec;

    SqlRuleParamsCodecTest() {
        var messages = new StaticMessageSource();
        messages.addMessage("error.sql_review_rule_params_invalid", Locale.getDefault(), "malformed");
        codec = new SqlRuleParamsCodec(new ObjectMapper(), messages);
    }

    @Test
    void decodesArraysAndToleratesScalars() {
        assertThat(codec.decode("{\"names\": [\"pg_sleep\", \"sleep\"], \"globs\": \"payroll.*\", \"none\": null}"))
                .containsEntry("names", List.of("pg_sleep", "sleep"))
                .containsEntry("globs", List.of("payroll.*"))
                .containsEntry("none", List.of());
        assertThat(codec.decode("{\"n\": [1, true]}")).containsEntry("n", List.of("1", "true"));
    }

    @Test
    void blankNullAndJsonNullDecodeToNoParams() {
        assertThat(codec.decode(null)).isEmpty();
        assertThat(codec.decode("  ")).isEmpty();
        assertThat(codec.decode("null")).isEmpty();
        assertThat(codec.decode("{}")).isEmpty();
    }

    @Test
    void rejectsNonObjectsNestedStructuresAndGarbage() {
        assertThatExceptionOfType(IllegalSqlReviewRulesetException.class)
                .isThrownBy(() -> codec.decode("[1]")).withMessage("malformed");
        assertThatExceptionOfType(IllegalSqlReviewRulesetException.class)
                .isThrownBy(() -> codec.decode("{\"names\": {\"a\": 1}}"));
        assertThatExceptionOfType(IllegalSqlReviewRulesetException.class)
                .isThrownBy(() -> codec.decode("{\"names\": [[1]]}"));
        assertThatExceptionOfType(IllegalSqlReviewRulesetException.class)
                .isThrownBy(() -> codec.decode("{not json")).withCauseInstanceOf(RuntimeException.class);
    }

    @Test
    void encodesListsAndCollapsesEmptyToNull() {
        assertThat(codec.encode(Map.of("globs", List.of("payroll.*")))).isEqualTo("{\"globs\":[\"payroll.*\"]}");
        assertThat(codec.encode(Map.of())).isNull();
        assertThat(codec.encode(null)).isNull();
        assertThat(codec.decode(codec.encode(Map.of("names", List.of("a", "b")))))
                .containsEntry("names", List.of("a", "b"));
    }
}
