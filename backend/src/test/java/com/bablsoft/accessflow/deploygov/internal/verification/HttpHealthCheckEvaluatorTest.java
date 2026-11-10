package com.bablsoft.accessflow.deploygov.internal.verification;

import com.bablsoft.accessflow.deploygov.api.HttpHealthCheck;
import com.bablsoft.accessflow.deploygov.api.VerificationCheckKind;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class HttpHealthCheckEvaluatorTest {

    private final AtomicReference<URI> fetched = new AtomicReference<>();

    private HttpHealthCheckEvaluator evaluator(FetchResult result) {
        return new HttpHealthCheckEvaluator((name, uri) -> {
            fetched.set(uri);
            return result;
        });
    }

    private static HttpHealthCheck check(String bodyContains) {
        return new HttpHealthCheck("health", "https://svc.example.com/healthz", 200, bodyContains);
    }

    @Test
    void passesOnTheExpectedStatus() {
        var sample = evaluator(new FetchResult.Response(200, "anything", false)).evaluate(check(null));

        assertThat(sample).isEqualTo(new VerificationSample("health", VerificationCheckKind.HTTP_HEALTH,
                true, "200", null));
        assertThat(fetched.get()).isEqualTo(URI.create("https://svc.example.com/healthz"));
    }

    @Test
    void passesWhenTheBodyContainsTheExpectedText() {
        var sample = evaluator(new FetchResult.Response(200, "{\"status\":\"ok\"}", false))
                .evaluate(check("\"ok\""));

        assertThat(sample.passed()).isTrue();
    }

    @Test
    void aBlankBodyContainsIsIgnored() {
        assertThat(evaluator(new FetchResult.Response(200, "", false)).evaluate(check("  ")).passed())
                .isTrue();
    }

    @Test
    void failsOnAStatusMismatch() {
        var sample = evaluator(new FetchResult.Response(503, "down", false)).evaluate(check(null));

        assertThat(sample.passed()).isFalse();
        assertThat(sample.observedValue()).isEqualTo("503");
        assertThat(sample.detail()).isEqualTo("expected status 200, got 503").doesNotContain("down");
    }

    @Test
    void aRedirectIsAFailedSampleNotAFollowedOne() {
        var sample = evaluator(new FetchResult.Response(302, "", false)).evaluate(check(null));

        assertThat(sample.passed()).isFalse();
        assertThat(sample.observedValue()).isEqualTo("302");
    }

    @Test
    void anExpectedRedirectStatusPasses() {
        var check = new HttpHealthCheck("health", "https://svc.example.com/", 302, null);

        assertThat(evaluator(new FetchResult.Response(302, "", false)).evaluate(check).passed()).isTrue();
    }

    @Test
    void failsOnABodyMismatchWithoutEchoingTheBody() {
        var sample = evaluator(new FetchResult.Response(200, "secret-payload", false)).evaluate(check("ok"));

        assertThat(sample.passed()).isFalse();
        assertThat(sample.observedValue()).isEqualTo("200");
        assertThat(sample.detail()).isEqualTo("body did not contain the expected text")
                .doesNotContain("secret-payload");
    }

    @Test
    void aTruncatedBodyMismatchSaysOnlyThePrefixWasSearched() {
        var sample = evaluator(new FetchResult.Response(200, "xxx", true)).evaluate(check("ok"));

        assertThat(sample.detail()).contains("first 64 KiB");
    }

    @Test
    void theTruncationMarkerNeverSatisfiesBodyContains() {
        var body = "xxx" + JdkVerificationHttpFetcher.TRUNCATED_MARKER;

        var sample = evaluator(new FetchResult.Response(200, body, true)).evaluate(check("[truncated]"));

        assertThat(sample.passed()).isFalse();
    }

    @Test
    void anUntruncatedBodyIsSearchedAsIs() {
        var body = "xxx" + JdkVerificationHttpFetcher.TRUNCATED_MARKER;

        assertThat(evaluator(new FetchResult.Response(200, body, false)).evaluate(check("[truncated]")).passed())
                .isTrue();
    }

    @Test
    void aTimeoutIsAFailedSample() {
        var sample = evaluator(new FetchResult.Failure("timeout")).evaluate(check(null));

        assertThat(sample).isEqualTo(new VerificationSample("health", VerificationCheckKind.HTTP_HEALTH,
                false, null, "timeout"));
    }

    @Test
    void aPolicyRejectionIsAFailedSample() {
        var sample = evaluator(new FetchResult.Failure("target rejected: restricted_address"))
                .evaluate(check(null));

        assertThat(sample.passed()).isFalse();
        assertThat(sample.detail()).isEqualTo("target rejected: restricted_address");
    }

    @Test
    void anUnparseableOrMissingUrlFailsWithoutFetching() {
        var evaluator = evaluator(new FetchResult.Response(200, "", false));

        assertThat(evaluator.evaluate(new HttpHealthCheck("health", "http://bad host/", 200, null)).detail())
                .isEqualTo("invalid url");
        assertThat(evaluator.evaluate(new HttpHealthCheck("health", null, 200, null)).detail())
                .isEqualTo("invalid url");
        assertThat(fetched.get()).isNull();
    }
}
