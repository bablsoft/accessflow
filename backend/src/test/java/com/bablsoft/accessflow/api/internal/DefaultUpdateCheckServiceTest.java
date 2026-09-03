package com.bablsoft.accessflow.api.internal;

import com.bablsoft.accessflow.api.internal.config.UpdateCheckProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class DefaultUpdateCheckServiceTest {

    private static final URI URL = URI.create("https://accessflow.io/version.json");
    private static final Instant T0 = Instant.parse("2026-09-03T10:00:00Z");
    private static final String MANIFEST_2_5 = """
            {"version":"2.5.0","released_at":"2026-09-20","changelog_url":"https://accessflow.io/changelog/#v2-5-0","extra":1}
            """;

    private MockRestServiceServer server;
    private RestClient restClient;
    private final SettableClock clock = new SettableClock(T0);
    private final AtomicInteger submissions = new AtomicInteger();
    private final Executor immediate = task -> {
        submissions.incrementAndGet();
        task.run();
    };

    @BeforeEach
    void setUp() {
        var builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        restClient = builder.build();
    }

    @Test
    void disabledAnswersUnknownWithoutTouchingExecutorOrNetwork() {
        var service = service("2.4.0", false, Duration.ofHours(24), immediate);

        var view = service.status();

        assertThat(view.status()).isEqualTo(UpdateCheckStatus.UNKNOWN);
        assertThat(view.updateAvailable()).isFalse();
        assertThat(view.currentVersion()).isEqualTo("2.4.0");
        assertThat(view.latestVersion()).isNull();
        assertThat(view.checkedAt()).isNull();
        assertThat(submissions).hasValue(0);
        server.verify();
    }

    @Test
    void firstCallIsUnknownAndTriggersOneRefreshThatReportsTheUpdate() {
        expectManifest(MANIFEST_2_5);
        var service = service("2.4.0", true, Duration.ofHours(24), immediate);

        var first = service.status();
        var second = service.status();

        assertThat(first.status()).isEqualTo(UpdateCheckStatus.UNKNOWN);
        assertThat(second.status()).isEqualTo(UpdateCheckStatus.UPDATE_AVAILABLE);
        assertThat(second.updateAvailable()).isTrue();
        assertThat(second.currentVersion()).isEqualTo("2.4.0");
        assertThat(second.latestVersion()).isEqualTo("2.5.0");
        assertThat(second.changelogUrl()).isEqualTo("https://accessflow.io/changelog/#v2-5-0");
        assertThat(second.checkedAt()).isEqualTo(T0);
        assertThat(submissions).hasValue(1);
        server.verify();
    }

    @Test
    void equalVersionIsUpToDate() {
        expectManifest("{\"version\":\"2.4.0\",\"changelog_url\":\"https://accessflow.io/changelog/#v2-4-0\"}");
        var service = service("2.4.0", true, Duration.ofHours(24), immediate);
        service.status();

        var view = service.status();

        assertThat(view.status()).isEqualTo(UpdateCheckStatus.UP_TO_DATE);
        assertThat(view.updateAvailable()).isFalse();
        assertThat(view.latestVersion()).isEqualTo("2.4.0");
    }

    @Test
    void newerLocalBuildIsUpToDate() {
        expectManifest("{\"version\":\"2.3.0\"}");
        var service = service("2.4.0", true, Duration.ofHours(24), immediate);
        service.status();

        assertThat(service.status().status()).isEqualTo(UpdateCheckStatus.UP_TO_DATE);
    }

    @Test
    void numericComparisonSeesTwoTenAsNewerThanTwoNine() {
        expectManifest("{\"version\":\"2.10.0\"}");
        var service = service("2.9.0", true, Duration.ofHours(24), immediate);
        service.status();

        assertThat(service.status().status()).isEqualTo(UpdateCheckStatus.UPDATE_AVAILABLE);
    }

    @Test
    void snapshotBuildNeverNags() {
        expectManifest(MANIFEST_2_5);
        var service = service("1.0.0-SNAPSHOT", true, Duration.ofHours(24), immediate);
        service.status();

        var view = service.status();

        assertThat(view.status()).isEqualTo(UpdateCheckStatus.UP_TO_DATE);
        assertThat(view.updateAvailable()).isFalse();
        assertThat(view.latestVersion()).isEqualTo("2.5.0");
    }

    @Test
    void preReleaseLocalBuildNeverNags() {
        expectManifest(MANIFEST_2_5);
        var service = service("2.5.0-beta.3", true, Duration.ofHours(24), immediate);
        service.status();

        assertThat(service.status().updateAvailable()).isFalse();
    }

    @Test
    void preReleaseManifestIsIgnored() {
        expectManifest("{\"version\":\"2.5.0-beta.1\"}");
        var service = service("2.4.0", true, Duration.ofHours(24), immediate);
        service.status();

        var view = service.status();

        assertThat(view.status()).isEqualTo(UpdateCheckStatus.UP_TO_DATE);
        assertThat(view.updateAvailable()).isFalse();
    }

    @Test
    void serverErrorResolvesToUnknownWithCheckedAt() {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));
        var service = service("2.4.0", true, Duration.ofHours(24), immediate);
        service.status();

        var view = service.status();

        assertThat(view.status()).isEqualTo(UpdateCheckStatus.UNKNOWN);
        assertThat(view.updateAvailable()).isFalse();
        assertThat(view.currentVersion()).isEqualTo("2.4.0");
        assertThat(view.checkedAt()).isEqualTo(T0);
        server.verify();
    }

    @Test
    void malformedJsonResolvesToUnknown() {
        expectManifest("<html>not json</html>");
        var service = service("2.4.0", true, Duration.ofHours(24), immediate);
        service.status();

        assertThat(service.status().status()).isEqualTo(UpdateCheckStatus.UNKNOWN);
    }

    @Test
    void jsonNullManifestResolvesToUnknownAndBacksOff() {
        expectManifest("null");
        var service = service("2.4.0", true, Duration.ofHours(24), immediate);
        service.status();

        var view = service.status();
        service.status();

        assertThat(view.status()).isEqualTo(UpdateCheckStatus.UNKNOWN);
        assertThat(view.checkedAt()).isEqualTo(T0);
        assertThat(submissions).hasValue(1);
        server.verify();
    }

    @Test
    void emptyBodyResolvesToUnknown() {
        expectManifest("");
        var service = service("2.4.0", true, Duration.ofHours(24), immediate);
        service.status();

        assertThat(service.status().status()).isEqualTo(UpdateCheckStatus.UNKNOWN);
    }

    @Test
    void oversizedBodyResolvesToUnknown() {
        var padding = "x".repeat(DefaultUpdateCheckService.MAX_BODY_BYTES + 1);
        expectManifest("{\"version\":\"2.5.0\",\"pad\":\"" + padding + "\"}");
        var service = service("2.4.0", true, Duration.ofHours(24), immediate);
        service.status();

        assertThat(service.status().status()).isEqualTo(UpdateCheckStatus.UNKNOWN);
    }

    @Test
    void nonSemverManifestVersionResolvesToUnknown() {
        expectManifest("{\"version\":\"latest\"}");
        var service = service("2.4.0", true, Duration.ofHours(24), immediate);
        service.status();

        assertThat(service.status().status()).isEqualTo(UpdateCheckStatus.UNKNOWN);
    }

    @Test
    void withinTtlServesTheSnapshotWithoutASecondRequest() {
        expectManifest(MANIFEST_2_5);
        var service = service("2.4.0", true, Duration.ofHours(24), immediate);
        service.status();
        clock.advance(Duration.ofHours(23));

        var view = service.status();
        service.status();

        assertThat(view.status()).isEqualTo(UpdateCheckStatus.UPDATE_AVAILABLE);
        assertThat(submissions).hasValue(1);
        server.verify();
    }

    @Test
    void afterTtlRefreshesAgain() {
        expectManifest("{\"version\":\"2.4.0\"}");
        expectManifest(MANIFEST_2_5);
        var service = service("2.4.0", true, Duration.ofHours(24), immediate);
        service.status();
        assertThat(service.status().status()).isEqualTo(UpdateCheckStatus.UP_TO_DATE);
        clock.advance(Duration.ofHours(25));

        var stale = service.status();
        var fresh = service.status();

        assertThat(stale.status()).isEqualTo(UpdateCheckStatus.UP_TO_DATE);
        assertThat(fresh.status()).isEqualTo(UpdateCheckStatus.UPDATE_AVAILABLE);
        assertThat(fresh.checkedAt()).isEqualTo(T0.plus(Duration.ofHours(25)));
        assertThat(submissions).hasValue(2);
        server.verify();
    }

    @Test
    void failedCheckBacksOffForOneTtl() {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.NOT_FOUND));
        var service = service("2.4.0", true, Duration.ofHours(24), immediate);
        service.status();
        clock.advance(Duration.ofHours(1));

        service.status();

        assertThat(submissions).hasValue(1);
        server.verify();
    }

    @Test
    void missingBuildMetadataStillFetchesButStaysUnknown() {
        expectManifest(MANIFEST_2_5);
        var service = service(null, true, Duration.ofHours(24), immediate);
        service.status();

        var view = service.status();

        assertThat(view.status()).isEqualTo(UpdateCheckStatus.UNKNOWN);
        assertThat(view.currentVersion()).isNull();
        assertThat(view.latestVersion()).isEqualTo("2.5.0");
        assertThat(view.updateAvailable()).isFalse();
    }

    @Test
    void concurrentCallsScheduleOnlyOneRefreshUntilItCompletes() {
        expectManifest(MANIFEST_2_5);
        List<Runnable> queued = new ArrayList<>();
        var service = service("2.4.0", true, Duration.ofHours(24), queued::add);

        service.status();
        service.status();
        assertThat(queued).hasSize(1);

        queued.getFirst().run();
        service.status();

        assertThat(queued).hasSize(1);
        assertThat(service.status().status()).isEqualTo(UpdateCheckStatus.UPDATE_AVAILABLE);
    }

    @Test
    void rejectedExecutionReleasesTheRefreshGuard() {
        Executor rejecting = task -> {
            submissions.incrementAndGet();
            throw new RejectedExecutionException("closed");
        };
        var service = service("2.4.0", true, Duration.ofHours(24), rejecting);

        service.status();
        service.status();

        assertThat(submissions).hasValue(2);
        server.verify();
    }

    private void expectManifest(String body) {
        server.expect(requestTo(URL))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Accept", MediaType.APPLICATION_JSON_VALUE))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
    }

    @SuppressWarnings("unchecked")
    private DefaultUpdateCheckService service(String currentVersion, boolean enabled, Duration ttl, Executor executor) {
        ObjectProvider<BuildProperties> provider = mock(ObjectProvider.class);
        if (currentVersion == null) {
            when(provider.getIfAvailable()).thenReturn(null);
        } else {
            var props = new Properties();
            props.setProperty("version", currentVersion);
            when(provider.getIfAvailable()).thenReturn(new BuildProperties(props));
        }
        var properties = new UpdateCheckProperties(enabled, URL, ttl, Duration.ofSeconds(5));
        return new DefaultUpdateCheckService(properties, restClient, executor, provider, clock,
                JsonMapper.builder().build());
    }

    /** A clock the test can move forward, standing in for the single UTC {@code Clock} bean. */
    private static final class SettableClock extends Clock {
        private Instant now;

        SettableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
