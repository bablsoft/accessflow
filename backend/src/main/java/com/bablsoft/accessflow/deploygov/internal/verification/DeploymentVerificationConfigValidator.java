package com.bablsoft.accessflow.deploygov.internal.verification;

import com.bablsoft.accessflow.deploygov.api.DeploymentVerificationConfig;
import com.bablsoft.accessflow.deploygov.api.HttpHealthCheck;
import com.bablsoft.accessflow.deploygov.api.IllegalDeploymentVerificationConfigException;
import com.bablsoft.accessflow.deploygov.api.PromqlThresholdCheck;
import com.bablsoft.accessflow.deploygov.api.VerificationCheck;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.HashSet;

/**
 * Write-time rules Bean Validation cannot express (#1024): window and interval bounds, a failure
 * threshold the window can actually reach, unique check names, and every URL parsing and passing
 * {@link VerificationTargetPolicy} — resolution included, so a typo fails now, not at sample time.
 * Throws on the first violation.
 */
@Component
@RequiredArgsConstructor
public class DeploymentVerificationConfigValidator {

    static final Duration MIN_WINDOW = Duration.ofMinutes(1);
    static final Duration MAX_WINDOW = Duration.ofHours(24);
    static final Duration MIN_SAMPLE_INTERVAL = Duration.ofSeconds(30);

    static final String WINDOW_OUT_OF_RANGE = "window_out_of_range";
    static final String SAMPLE_INTERVAL_OUT_OF_RANGE = "sample_interval_out_of_range";
    static final String FAILURE_THRESHOLD_EXCEEDS_SAMPLES = "failure_threshold_exceeds_samples";
    static final String DUPLICATE_CHECK_NAME = "duplicate_check_name";
    static final String INVALID_URL = "invalid_url";

    private final VerificationTargetPolicy targetPolicy;

    public void validate(DeploymentVerificationConfig config) {
        var window = config.window();
        var interval = config.sampleInterval();
        if (window == null || window.compareTo(MIN_WINDOW) < 0 || window.compareTo(MAX_WINDOW) > 0) {
            throw new IllegalDeploymentVerificationConfigException(WINDOW_OUT_OF_RANGE, null);
        }
        if (interval == null || interval.compareTo(MIN_SAMPLE_INTERVAL) < 0 || interval.compareTo(window) > 0) {
            throw new IllegalDeploymentVerificationConfigException(SAMPLE_INTERVAL_OUT_OF_RANGE, null);
        }
        if (config.failureThreshold() > window.dividedBy(interval)) {
            throw new IllegalDeploymentVerificationConfigException(FAILURE_THRESHOLD_EXCEEDS_SAMPLES, null);
        }
        var names = new HashSet<String>();
        for (var check : config.checks()) {
            if (!names.add(check.name())) {
                throw new IllegalDeploymentVerificationConfigException(DUPLICATE_CHECK_NAME, check.name());
            }
            validateTarget(check);
        }
    }

    private void validateTarget(VerificationCheck check) {
        var url = switch (check) {
            case HttpHealthCheck http -> http.url();
            case PromqlThresholdCheck promql -> promql.prometheusUrl();
        };
        var uri = parse(url, check.name());
        // The evaluator appends /api/v1/query?query=… to the base, so it cannot carry its own.
        if (check instanceof PromqlThresholdCheck && (uri.getRawQuery() != null || uri.getRawFragment() != null)) {
            throw new IllegalDeploymentVerificationConfigException(INVALID_URL, check.name());
        }
        targetPolicy.check(uri).ifPresent(reason -> {
            throw new IllegalDeploymentVerificationConfigException(reason, check.name());
        });
    }

    private static URI parse(String url, String checkName) {
        try {
            var uri = new URI(url == null ? "" : url.strip());
            if (!uri.isAbsolute() || uri.isOpaque()) {
                throw new IllegalDeploymentVerificationConfigException(INVALID_URL, checkName);
            }
            return uri;
        } catch (URISyntaxException ex) {
            throw new IllegalDeploymentVerificationConfigException(INVALID_URL, checkName);
        }
    }
}
