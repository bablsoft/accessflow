package com.bablsoft.accessflow.deploygov.internal.verification;

import com.bablsoft.accessflow.deploygov.api.HttpHealthCheck;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URISyntaxException;

/**
 * GETs the check's URL; passes iff the status equals {@code expectedStatus} and, when
 * {@code bodyContains} is set, the (capped) body contains it. A 3xx is only an observed status —
 * redirects are never followed.
 */
@Component
@RequiredArgsConstructor
final class HttpHealthCheckEvaluator implements VerificationCheckEvaluator<HttpHealthCheck> {

    private final VerificationHttpFetcher fetcher;

    @Override
    public VerificationSample evaluate(HttpHealthCheck check) {
        if (check.url() == null) {
            return failed(check, null, "invalid url");
        }
        URI uri;
        try {
            uri = new URI(check.url().strip());
        } catch (URISyntaxException ex) {
            return failed(check, null, "invalid url");
        }
        return switch (fetcher.fetch(check.name(), uri)) {
            case FetchResult.Failure failure -> failed(check, null, failure.reason());
            case FetchResult.Response response -> judge(check, response);
        };
    }

    private static VerificationSample judge(HttpHealthCheck check, FetchResult.Response response) {
        var observed = Integer.toString(response.status());
        if (response.status() != check.expectedStatus()) {
            return failed(check, observed,
                    "expected status " + check.expectedStatus() + ", got " + response.status());
        }
        var expectedText = check.bodyContains();
        if (expectedText != null && !expectedText.isBlank() && !searchable(response).contains(expectedText)) {
            return failed(check, observed, response.truncated()
                    ? "body did not contain the expected text (searched the first 64 KiB)"
                    : "body did not contain the expected text");
        }
        return new VerificationSample(check.name(), check.kind(), true, observed, null);
    }

    /** The body as received — never the fetcher's truncation marker, which must not match. */
    private static String searchable(FetchResult.Response response) {
        var body = response.body();
        var marker = JdkVerificationHttpFetcher.TRUNCATED_MARKER;
        return response.truncated() && body.endsWith(marker)
                ? body.substring(0, body.length() - marker.length()) : body;
    }

    private static VerificationSample failed(HttpHealthCheck check, String observed, String detail) {
        return new VerificationSample(check.name(), check.kind(), false, observed, detail);
    }
}
