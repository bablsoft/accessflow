package com.bablsoft.accessflow.deploygov.internal.verification;

import com.bablsoft.accessflow.deploygov.api.PromqlThresholdCheck;
import com.bablsoft.accessflow.deploygov.api.VerificationCheckKind;
import com.bablsoft.accessflow.deploygov.api.VerificationComparator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class PromqlThresholdCheckEvaluatorTest {

    private final AtomicReference<URI> fetched = new AtomicReference<>();

    private PromqlThresholdCheckEvaluator evaluator(FetchResult result) {
        return new PromqlThresholdCheckEvaluator((name, uri) -> {
            fetched.set(uri);
            return result;
        }, JsonMapper.builder().build());
    }

    private static PromqlThresholdCheck check(VerificationComparator comparator, double threshold,
                                              boolean noDataIsFailure) {
        return new PromqlThresholdCheck("error-rate", "https://prom.example.com/",
                "sum(rate(http_errors_total[5m]))", comparator, threshold, noDataIsFailure);
    }

    private static PromqlThresholdCheck check() {
        return check(VerificationComparator.LT, 0.05, true);
    }

    private static FetchResult vector(String... values) {
        var series = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                series.append(',');
            }
            series.append("{\"metric\":{},\"value\":[1730000000.123,\"").append(values[i]).append("\"]}");
        }
        return ok("{\"status\":\"success\",\"data\":{\"resultType\":\"vector\",\"result\":[" + series + "]}}");
    }

    private static FetchResult ok(String body) {
        return new FetchResult.Response(200, body, false);
    }

    @Test
    void passesWhenTheValueSatisfiesTheThreshold() {
        var sample = evaluator(vector("0.01")).evaluate(check());

        assertThat(sample).isEqualTo(new VerificationSample("error-rate",
                VerificationCheckKind.PROMQL_THRESHOLD, true, "0.01", null));
    }

    @Test
    void buildsTheInstantQueryUrlWithoutADoubleSlashAndEncodesTheQuery() {
        evaluator(vector("0.01")).evaluate(check());

        assertThat(fetched.get().toString()).isEqualTo("https://prom.example.com/api/v1/query?query="
                + "sum%28rate%28http_errors_total%5B5m%5D%29%29");
    }

    @Test
    void failsWhenTheValueBreaksTheThreshold() {
        var sample = evaluator(vector("0.2")).evaluate(check());

        assertThat(sample.passed()).isFalse();
        assertThat(sample.observedValue()).isEqualTo("0.2");
        assertThat(sample.detail()).isEqualTo("value 0.2 is not < 0.05");
    }

    @ParameterizedTest
    @CsvSource({
            "LT, 1, 2, true", "LT, 2, 2, false", "LT, 3, 2, false",
            "LTE, 1, 2, true", "LTE, 2, 2, true", "LTE, 3, 2, false",
            "GT, 3, 2, true", "GT, 2, 2, false", "GT, 1, 2, false",
            "GTE, 3, 2, true", "GTE, 2, 2, true", "GTE, 1, 2, false"})
    void everyComparatorIncludingItsEqualityEdge(VerificationComparator comparator, String value,
                                                 double threshold, boolean expected) {
        var sample = evaluator(vector(value)).evaluate(check(comparator, threshold, true));

        assertThat(sample.passed()).isEqualTo(expected);
        if (!expected) {
            assertThat(sample.detail()).startsWith("value " + value + " is not ");
        }
    }

    @Test
    void anEmptyVectorFailsWhenNoDataIsFailure() {
        var sample = evaluator(vector()).evaluate(check(VerificationComparator.LT, 0.05, true));

        assertThat(sample.passed()).isFalse();
        assertThat(sample.detail()).isEqualTo("no data");
        assertThat(sample.observedValue()).isNull();
    }

    @Test
    void anEmptyVectorPassesWhenNoDataIsNotFailure() {
        var sample = evaluator(vector()).evaluate(check(VerificationComparator.LT, 0.05, false));

        assertThat(sample.passed()).isTrue();
        assertThat(sample.detail()).isEqualTo("no data");
    }

    @Test
    void aNaNValueFails() {
        var sample = evaluator(vector("NaN")).evaluate(check());

        assertThat(sample.passed()).isFalse();
        assertThat(sample.detail()).isEqualTo("sample value is NaN");
        assertThat(sample.observedValue()).isEqualTo("NaN");
    }

    @Test
    void anUnparseableValueFails() {
        var sample = evaluator(vector("abc")).evaluate(check());

        assertThat(sample.passed()).isFalse();
        assertThat(sample.detail()).isEqualTo("sample value is not a number");
    }

    @Test
    void infinityIsComparedNotRejected() {
        assertThat(evaluator(vector("+Inf")).evaluate(check()).passed()).isFalse();
        assertThat(evaluator(vector("-Inf")).evaluate(check()).passed()).isTrue();
    }

    @Test
    void aMissingSampleValueFails() {
        var sample = evaluator(ok("{\"status\":\"success\",\"data\":{\"resultType\":\"vector\","
                + "\"result\":[{\"metric\":{}}]}}")).evaluate(check());

        assertThat(sample.passed()).isFalse();
        assertThat(sample.detail()).isEqualTo("prometheus sample has no value");
    }

    @Test
    void multipleSeriesEvaluateTheFirstAndSaySo() {
        var passing = evaluator(vector("0.01", "0.9")).evaluate(check());
        var failing = evaluator(vector("0.9", "0.01")).evaluate(check());

        assertThat(passing.passed()).isTrue();
        assertThat(passing.detail()).isEqualTo("2 series returned; evaluated the first");
        assertThat(failing.passed()).isFalse();
        assertThat(failing.detail()).isEqualTo("value 0.9 is not < 0.05 (2 series returned; evaluated the first)");
    }

    @Test
    void anErrorStatusFails() {
        var sample = evaluator(new FetchResult.Response(200,
                "{\"status\":\"error\",\"errorType\":\"bad_data\",\"error\":\"parse error\"}", false))
                .evaluate(check());

        assertThat(sample.passed()).isFalse();
        assertThat(sample.detail()).isEqualTo("prometheus query status is not success");
    }

    @Test
    void aNon2xxFails() {
        var sample = evaluator(new FetchResult.Response(400, "{\"status\":\"error\"}", false)).evaluate(check());

        assertThat(sample.passed()).isFalse();
        assertThat(sample.detail()).isEqualTo("prometheus answered status 400");
    }

    @Test
    void aRedirectFails() {
        var sample = evaluator(new FetchResult.Response(301, "", false)).evaluate(check());

        assertThat(sample.passed()).isFalse();
        assertThat(sample.detail()).isEqualTo("prometheus answered status 301");
    }

    @Test
    void invalidJsonFails() {
        var sample = evaluator(ok("<html>not json</html>")).evaluate(check());

        assertThat(sample.passed()).isFalse();
        assertThat(sample.detail()).isEqualTo("prometheus response is not valid JSON");
    }

    @Test
    void aTruncatedInvalidBodySaysItWasTruncated() {
        var sample = evaluator(new FetchResult.Response(200, "{\"status\":\"succ", true)).evaluate(check());

        assertThat(sample.detail()).isEqualTo("prometheus response is not valid JSON (truncated at 64 KiB)");
    }

    @Test
    void aPrometheusUrlWithAQueryOrFragmentFailsWithoutFetching() {
        var evaluator = evaluator(vector("0.01"));

        for (var url : new String[] {"http://prom:9090/?a=b", "http://prom:9090/#x"}) {
            var check = new PromqlThresholdCheck("error-rate", url, "up", VerificationComparator.LT, 1, true);
            assertThat(evaluator.evaluate(check).detail()).isEqualTo("invalid prometheus url");
        }
        assertThat(fetched.get()).isNull();
    }

    @Test
    void aNonVectorResultFails() {
        var matrix = evaluator(ok("{\"status\":\"success\",\"data\":{\"resultType\":\"scalar\","
                + "\"result\":[1730000000,\"1\"]}}")).evaluate(check());
        var notArray = evaluator(ok("{\"status\":\"success\",\"data\":{\"resultType\":\"vector\","
                + "\"result\":{}}}")).evaluate(check());

        assertThat(matrix.detail()).isEqualTo("prometheus result is not an instant vector");
        assertThat(notArray.detail()).isEqualTo("prometheus result is not an instant vector");
        assertThat(matrix.passed()).isFalse();
        assertThat(notArray.passed()).isFalse();
    }

    @Test
    void aTimeoutFails() {
        var sample = evaluator(new FetchResult.Failure("timeout")).evaluate(check());

        assertThat(sample).isEqualTo(new VerificationSample("error-rate",
                VerificationCheckKind.PROMQL_THRESHOLD, false, null, "timeout"));
    }

    @Test
    void anInvalidPrometheusUrlFailsWithoutFetching() {
        var evaluator = evaluator(vector("0.01"));

        var bad = new PromqlThresholdCheck("error-rate", "http://bad host", "up",
                VerificationComparator.LT, 1, true);
        var missing = new PromqlThresholdCheck("error-rate", null, "up", VerificationComparator.LT, 1, true);
        var noQuery = new PromqlThresholdCheck("error-rate", "https://prom", null,
                VerificationComparator.LT, 1, true);

        assertThat(evaluator.evaluate(bad).detail()).isEqualTo("invalid prometheus url");
        assertThat(evaluator.evaluate(missing).detail()).isEqualTo("invalid prometheus url");
        assertThat(evaluator.evaluate(noQuery).detail()).isEqualTo("invalid prometheus url");
        assertThat(fetched.get()).isNull();
    }
}
