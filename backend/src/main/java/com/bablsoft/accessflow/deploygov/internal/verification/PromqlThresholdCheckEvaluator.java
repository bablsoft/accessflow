package com.bablsoft.accessflow.deploygov.internal.verification;

import com.bablsoft.accessflow.deploygov.api.PromqlThresholdCheck;
import com.bablsoft.accessflow.deploygov.api.VerificationComparator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Runs the check's instant query against Prometheus' {@code /api/v1/query} and compares the first
 * series' value against the threshold. Fails closed on the data: non-2xx, unparseable JSON,
 * {@code status != "success"}, a non-vector result, an unparseable or {@code NaN} value — and an
 * empty vector when {@code noDataIsFailure}.
 */
@Component
@RequiredArgsConstructor
final class PromqlThresholdCheckEvaluator implements VerificationCheckEvaluator<PromqlThresholdCheck> {

    static final String NO_DATA = "no data";

    private final VerificationHttpFetcher fetcher;
    private final ObjectMapper objectMapper;

    @Override
    public VerificationSample evaluate(PromqlThresholdCheck check) {
        if (check.prometheusUrl() == null || check.query() == null) {
            return failed(check, null, "invalid prometheus url");
        }
        URI uri;
        try {
            uri = queryUri(check);
        } catch (IllegalArgumentException ex) {
            return failed(check, null, "invalid prometheus url");
        }
        return switch (fetcher.fetch(check.name(), uri)) {
            case FetchResult.Failure failure -> failed(check, null, failure.reason());
            case FetchResult.Response response -> judge(check, response);
        };
    }

    static URI queryUri(PromqlThresholdCheck check) {
        var base = check.prometheusUrl().strip();
        var parsed = URI.create(base);
        if (parsed.getRawQuery() != null || parsed.getRawFragment() != null) {
            throw new IllegalArgumentException("prometheus url carries a query or fragment");
        }
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return URI.create(base + "/api/v1/query?query="
                + URLEncoder.encode(check.query(), StandardCharsets.UTF_8));
    }

    private VerificationSample judge(PromqlThresholdCheck check, FetchResult.Response response) {
        if (response.status() < 200 || response.status() > 299) {
            return failed(check, null, "prometheus answered status " + response.status());
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(response.body());
        } catch (JacksonException ex) {
            return failed(check, null, response.truncated()
                    ? "prometheus response is not valid JSON (truncated at 64 KiB)"
                    : "prometheus response is not valid JSON");
        }
        if (root == null || !"success".equals(root.path("status").asString(""))) {
            return failed(check, null, "prometheus query status is not success");
        }
        var data = root.path("data");
        if (!"vector".equals(data.path("resultType").asString(""))) {
            return failed(check, null, "prometheus result is not an instant vector");
        }
        var result = data.path("result");
        if (!result.isArray()) {
            return failed(check, null, "prometheus result is not an instant vector");
        }
        if (result.isEmpty()) {
            return new VerificationSample(check.name(), check.kind(), !check.noDataIsFailure(), null,
                    NO_DATA);
        }
        var valueNode = result.get(0).path("value").path(1);
        if (!valueNode.isString()) {
            return failed(check, null, "prometheus sample has no value");
        }
        var raw = valueNode.asString();
        double value;
        try {
            value = parseSampleValue(raw);
        } catch (NumberFormatException ex) {
            return failed(check, raw, "sample value is not a number");
        }
        if (Double.isNaN(value)) {
            return failed(check, raw, "sample value is NaN");
        }
        var passed = compare(value, check.comparator(), check.threshold());
        var detail = passed ? null : "value " + raw + " is not " + symbol(check.comparator()) + " "
                + check.threshold();
        if (result.size() > 1) {
            var note = result.size() + " series returned; evaluated the first";
            detail = detail == null ? note : detail + " (" + note + ")";
        }
        return new VerificationSample(check.name(), check.kind(), passed, raw, detail);
    }

    /** Prometheus renders infinities as {@code +Inf} / {@code -Inf}, which Java does not parse. */
    static double parseSampleValue(String raw) {
        return switch (raw) {
            case "+Inf", "Inf" -> Double.POSITIVE_INFINITY;
            case "-Inf" -> Double.NEGATIVE_INFINITY;
            default -> Double.parseDouble(raw);
        };
    }

    static boolean compare(double value, VerificationComparator comparator, double threshold) {
        return switch (comparator) {
            case LT -> value < threshold;
            case LTE -> value <= threshold;
            case GT -> value > threshold;
            case GTE -> value >= threshold;
        };
    }

    private static String symbol(VerificationComparator comparator) {
        return switch (comparator) {
            case LT -> "<";
            case LTE -> "<=";
            case GT -> ">";
            case GTE -> ">=";
        };
    }

    private static VerificationSample failed(PromqlThresholdCheck check, String observed, String detail) {
        return new VerificationSample(check.name(), check.kind(), false, observed, detail);
    }
}
