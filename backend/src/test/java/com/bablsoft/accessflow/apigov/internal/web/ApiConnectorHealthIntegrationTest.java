package com.bablsoft.accessflow.apigov.internal.web;

import com.bablsoft.accessflow.TestcontainersConfig;
import com.bablsoft.accessflow.apigov.api.ApiAuthMethod;
import com.bablsoft.accessflow.apigov.api.ApiConnectorHealthService;
import com.bablsoft.accessflow.apigov.api.ApiConnectorHealthStatus;
import com.bablsoft.accessflow.apigov.api.ApiProtocol;
import com.bablsoft.accessflow.apigov.internal.config.ApigovRequestProperties;
import com.bablsoft.accessflow.apigov.internal.persistence.entity.ApiConnectorEntity;
import com.bablsoft.accessflow.apigov.internal.persistence.repo.ApiConnectorHealthCheckRepository;
import com.bablsoft.accessflow.apigov.internal.persistence.repo.ApiConnectorRepository;
import com.bablsoft.accessflow.apigov.internal.scheduled.ApiConnectorHealthJob;
import com.bablsoft.accessflow.core.api.AuthProviderType;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.core.internal.persistence.entity.OrganizationEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.UserEntity;
import com.bablsoft.accessflow.core.internal.persistence.repo.OrganizationRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.UserRepository;
import com.bablsoft.accessflow.security.internal.jwt.JwtService;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.web.context.WebApplicationContext;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

/**
 * Scheduled connector health end to end (#894): a monitored connector goes down, three failing ticks
 * flip it to {@code UNHEALTHY} with one audit row, the history is kept, and the admin read API
 * reports it.
 */
@SpringBootTest
@ImportTestcontainers(TestcontainersConfig.class)
class ApiConnectorHealthIntegrationTest {

    @Autowired WebApplicationContext context;
    @Autowired OrganizationRepository organizationRepository;
    @Autowired UserRepository userRepository;
    @Autowired ApiConnectorRepository connectorRepository;
    @Autowired ApiConnectorHealthCheckRepository healthCheckRepository;
    @Autowired ApiConnectorHealthService healthService;
    @Autowired ApigovRequestProperties properties;
    @Autowired Clock clock;
    @Autowired JwtService jwtService;
    @Autowired JdbcTemplate jdbcTemplate;

    private MockMvcTester mvc;
    private HttpServer upstream;
    private SteppedClock jobClock;
    private ApiConnectorHealthJob job;
    private OrganizationEntity org;
    private UserEntity admin;
    private UserEntity analyst;
    private final List<ApiConnectorEntity> connectors = new ArrayList<>();
    private String baseUrl;

    @BeforeEach
    void setUp() throws IOException {
        mvc = MockMvcTester.from(context, builder -> builder.apply(springSecurity()).build());
        upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext("/", exchange -> {
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        upstream.start();
        baseUrl = "http://127.0.0.1:" + upstream.getAddress().getPort() + "/";
        jobClock = new SteppedClock(clock.instant());
        // Built directly rather than through the context's proxy: ShedLock's lockAtLeastFor would
        // otherwise skip every run after the first, across all classes sharing the test Redis.
        job = new ApiConnectorHealthJob(connectorRepository, healthCheckRepository, healthService, properties,
                jobClock);

        var suffix = UUID.randomUUID().toString().substring(0, 8);
        org = new OrganizationEntity();
        org.setId(UUID.randomUUID());
        org.setName("Health " + suffix);
        org.setSlug("health-" + suffix);
        org = organizationRepository.save(org);
        admin = saveUser("admin-health-" + suffix + "@example.com", UserRoleType.ADMIN);
        analyst = saveUser("analyst-health-" + suffix + "@example.com", UserRoleType.ANALYST);
    }

    @AfterEach
    void cleanup() {
        upstream.stop(0);
        jdbcTemplate.update("delete from audit_log where organization_id = ?", org.getId());
        for (var connector : connectors) {
            connectorRepository.deleteById(connector.getId());
        }
        userRepository.deleteAllById(List.of(admin.getId(), analyst.getId()));
        organizationRepository.deleteById(org.getId());
    }

    @Test
    void consecutiveFailuresFlipToUnhealthyOnceAndRecoveryIsAudited() throws IOException {
        var monitored = saveConnector(true, 5);
        var optedOut = saveConnector(true, null);
        var inactive = saveConnector(false, 5);

        job.run();
        assertThat(reload(monitored).getLastProbeStatus()).isEqualTo(ApiConnectorHealthStatus.HEALTHY);

        upstream.stop(0);
        for (var i = 0; i < 4; i++) {
            jobClock.advance(Duration.ofMinutes(6));
            job.run();
        }

        var down = reload(monitored);
        assertThat(down.getLastProbeStatus()).isEqualTo(ApiConnectorHealthStatus.UNHEALTHY);
        assertThat(down.getConsecutiveProbeFailures()).isEqualTo(4);
        assertThat(down.getLastProbeAt()).isNotNull();
        assertThat(auditCount(monitored, "API_CONNECTOR_UNHEALTHY")).isEqualTo(1);
        var history = healthCheckRepository.findTop20ByConnectorIdOrderByCheckedAtDesc(monitored.getId());
        assertThat(history).hasSize(5);
        assertThat(history.subList(0, 4)).allMatch(check -> !check.isSuccess());
        assertThat(history.get(4).isSuccess()).isTrue();

        assertThat(healthCheckRepository.findTop20ByConnectorIdOrderByCheckedAtDesc(optedOut.getId())).isEmpty();
        assertThat(healthCheckRepository.findTop20ByConnectorIdOrderByCheckedAtDesc(inactive.getId())).isEmpty();
        assertThat(reload(optedOut).getLastProbeStatus()).isEqualTo(ApiConnectorHealthStatus.UNKNOWN);

        // An admin edit of the connector must not write back the probe state it read earlier.
        var stale = reload(monitored);
        stale.setLastProbeStatus(ApiConnectorHealthStatus.HEALTHY);
        stale.setConsecutiveProbeFailures(0);
        stale.setTimeoutMs(1500);
        connectorRepository.save(stale);
        assertThat(reload(monitored).getLastProbeStatus()).isEqualTo(ApiConnectorHealthStatus.UNHEALTHY);
        assertThat(reload(monitored).getTimeoutMs()).isEqualTo(1500);

        var adminToken = token(admin);
        assertThat(mvc.get().uri("/api/v1/api-connectors/{id}/health", monitored.getId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .hasStatusOk()
                .bodyJson()
                .satisfies(json -> {
                    json.assertThat().extractingPath("$.status").isEqualTo("UNHEALTHY");
                    json.assertThat().extractingPath("$.consecutive_failures").isEqualTo(4);
                    json.assertThat().extractingPath("$.health_check_interval_minutes").isEqualTo(5);
                    json.assertThat().extractingPath("$.recent_checks.length()").isEqualTo(5);
                });
        assertThat(mvc.get().uri("/api/v1/api-connectors/{id}/health", monitored.getId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(analyst)))
                .hasStatus(403);
        assertThat(mvc.get().uri("/api/v1/api-connectors/{id}/health", UUID.randomUUID())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .hasStatus(404);

        restartUpstream();
        var rebound = reload(monitored);
        rebound.setBaseUrl(baseUrl);
        connectorRepository.save(rebound);
        jobClock.advance(Duration.ofMinutes(6));
        job.run();

        var recovered = reload(monitored);
        assertThat(recovered.getLastProbeStatus()).isEqualTo(ApiConnectorHealthStatus.HEALTHY);
        assertThat(recovered.getConsecutiveProbeFailures()).isZero();
        assertThat(auditCount(monitored, "API_CONNECTOR_RECOVERED")).isEqualTo(1);
        assertThat(auditCount(monitored, "API_CONNECTOR_UNHEALTHY")).isEqualTo(1);
    }

    private void restartUpstream() throws IOException {
        upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext("/", exchange -> {
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        upstream.start();
        baseUrl = "http://127.0.0.1:" + upstream.getAddress().getPort() + "/";
    }

    private int auditCount(ApiConnectorEntity connector, String action) {
        var count = jdbcTemplate.queryForObject(
                "select count(*) from audit_log where resource_id = ? and action = ?",
                Integer.class, connector.getId(), action);
        return count == null ? 0 : count;
    }

    private ApiConnectorEntity reload(ApiConnectorEntity connector) {
        return connectorRepository.findById(connector.getId()).orElseThrow();
    }

    private ApiConnectorEntity saveConnector(boolean active, Integer intervalMinutes) {
        var entity = new ApiConnectorEntity();
        entity.setId(UUID.randomUUID());
        entity.setOrganizationId(org.getId());
        entity.setName("health-" + UUID.randomUUID().toString().substring(0, 8));
        entity.setProtocol(ApiProtocol.REST);
        entity.setBaseUrl(baseUrl);
        entity.setAuthMethod(ApiAuthMethod.NONE);
        entity.setTimeoutMs(2000);
        entity.setActive(active);
        entity.setHealthCheckIntervalMinutes(intervalMinutes);
        var saved = connectorRepository.save(entity);
        connectors.add(saved);
        return saved;
    }

    private UserEntity saveUser(String email, UserRoleType role) {
        var entity = new UserEntity();
        entity.setId(UUID.randomUUID());
        entity.setEmail(email);
        entity.setDisplayName(email);
        entity.setPasswordHash("x");
        entity.setRole(role);
        entity.setAuthProvider(AuthProviderType.LOCAL);
        entity.setActive(true);
        entity.setOrganization(org);
        return userRepository.save(entity);
    }

    private String token(UserEntity entity) {
        var view = new com.bablsoft.accessflow.core.api.UserView(
                entity.getId(), entity.getEmail(), entity.getDisplayName(), entity.getRole(),
                entity.getOrganization().getId(), entity.isActive(), entity.getAuthProvider(),
                entity.getPasswordHash(), entity.getLastLoginAt(), entity.getPreferredLanguage(),
                entity.isTotpEnabled(), entity.getCreatedAt());
        return jwtService.generateAccessToken(view);
    }

    private static final class SteppedClock extends Clock {

        private Instant now;

        SteppedClock(Instant start) {
            this.now = start;
        }

        void advance(Duration step) {
            now = now.plus(step);
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
