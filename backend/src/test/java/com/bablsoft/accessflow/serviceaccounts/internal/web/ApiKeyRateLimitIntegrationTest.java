package com.bablsoft.accessflow.serviceaccounts.internal.web;

import com.bablsoft.accessflow.TestcontainersConfig;
import com.bablsoft.accessflow.core.api.AuthProviderType;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.core.api.UserView;
import com.bablsoft.accessflow.core.internal.persistence.entity.OrganizationEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.UserEntity;
import com.bablsoft.accessflow.core.internal.persistence.repo.OrganizationRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.UserRepository;
import com.bablsoft.accessflow.security.api.ApiKeyService;
import com.bablsoft.accessflow.security.internal.jwt.JwtService;
import com.bablsoft.accessflow.serviceaccounts.api.ServiceAccountProvisioningService;
import com.bablsoft.accessflow.serviceaccounts.api.ServiceAccountSource;
import com.bablsoft.accessflow.serviceaccounts.internal.persistence.repo.ServiceAccountRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Drives real HTTP through the embedded servlet container (#873): the rate-limit filter is a
 * {@code FilterRegistrationBean} at order 0, which MockMvc does not include, and the claim under test
 * is precisely that it runs inside Spring Security's chain (-100) with the API-key principal in
 * scope. Counters are seeded straight into Redis for the current <i>and</i> next epoch minute so the
 * assertions never depend on a wall-clock minute boundary.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ImportTestcontainers(TestcontainersConfig.class)
class ApiKeyRateLimitIntegrationTest {

    private static final String KEY_PREFIX = "accessflow:serviceaccounts:ratelimit:";

    @LocalServerPort int port;
    @Autowired UserRepository userRepository;
    @Autowired OrganizationRepository organizationRepository;
    @Autowired ServiceAccountRepository serviceAccountRepository;
    @Autowired ServiceAccountProvisioningService provisioningService;
    @Autowired ApiKeyService apiKeyService;
    @Autowired JwtService jwtService;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired StringRedisTemplate redisTemplate;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired ObjectMapper objectMapper;

    private OrganizationEntity org;
    private UserEntity human;
    private UserEntity bot;
    private UserEntity otherBot;
    private String humanKey;
    private String botKey;
    private String otherBotKey;

    @BeforeEach
    void setUp() {
        var suffix = UUID.randomUUID().toString();
        org = new OrganizationEntity();
        org.setId(UUID.randomUUID());
        org.setName("ratelimit-" + suffix);
        org.setSlug("ratelimit-" + suffix);
        organizationRepository.save(org);

        human = saveUser("human-" + suffix + "@example.com");
        humanKey = apiKeyService.issue(human.getId(), org.getId(), "human", null).rawKey();

        bot = saveUser("bot-" + suffix + "@example.com");
        provisioningService.ensureRegistered(org.getId(), bot.getId(), ServiceAccountSource.UI);
        botKey = apiKeyService.issue(bot.getId(), org.getId(), "bot", null).rawKey();

        otherBot = saveUser("bot2-" + suffix + "@example.com");
        provisioningService.ensureRegistered(org.getId(), otherBot.getId(), ServiceAccountSource.UI);
        otherBotKey = apiKeyService.issue(otherBot.getId(), org.getId(), "bot2", null).rawKey();
    }

    @AfterEach
    void cleanup() {
        for (var user : new UserEntity[] {human, bot, otherBot}) {
            var keys = redisTemplate.keys(KEY_PREFIX + user.getId() + ":*");
            if (keys != null && !keys.isEmpty()) {
                redisTemplate.delete(keys);
            }
        }
        // users cascades api_keys and service_accounts.
        jdbcTemplate.update("DELETE FROM audit_log WHERE organization_id = ?", org.getId());
        jdbcTemplate.update("DELETE FROM users WHERE organization_id = ?", org.getId());
        jdbcTemplate.update("DELETE FROM organizations WHERE id = ?", org.getId());
    }

    @Test
    void freshServiceAccountIsUnderTheLimitAndCounted() throws Exception {
        var response = get("X-API-Key", botKey);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(redisTemplate.keys(KEY_PREFIX + bot.getId() + ":*")).isNotEmpty();
    }

    @Test
    void exhaustedServiceAccountGets429WithRetryAfterAndProblemDetail() throws Exception {
        perMinuteLimit(bot, 1);
        seedMinuteCounters(bot, 1);

        var response = get("X-API-Key", botKey);

        assertThat(response.statusCode()).isEqualTo(429);
        assertThat(response.headers().firstValue("Content-Type")).hasValue("application/problem+json");
        var retryAfter = response.headers().firstValue("Retry-After").map(Long::parseLong).orElseThrow();
        assertThat(retryAfter).isBetween(1L, 60L);
        var json = objectMapper.readTree(response.body());
        assertThat(json.get("status").asInt()).isEqualTo(429);
        assertThat(json.get("error").asString()).isEqualTo("SERVICE_ACCOUNT_RATE_LIMIT_EXCEEDED");
        assertThat(json.get("limit").asInt()).isEqualTo(1);
        assertThat(json.get("retryAfterSeconds").asLong()).isEqualTo(retryAfter);
        assertThat(json.get("detail").asString()).contains("1 requests per minute");
        assertThat(json.get("timestamp").asString()).isNotBlank();
        assertThat(json.get("traceId").asString()).isNotBlank();
    }

    @Test
    void limitsAreIsolatedPerIdentity() throws Exception {
        perMinuteLimit(bot, 1);
        seedMinuteCounters(bot, 1);

        assertThat(get("X-API-Key", botKey).statusCode()).isEqualTo(429);
        assertThat(get("X-API-Key", otherBotKey).statusCode()).isEqualTo(200);
    }

    @Test
    void jwtSessionIsNeverLimitedWhileTheSameUsersApiKeyIs() throws Exception {
        // Above the deployment default (120/min) a human's personal key has no detail row to override.
        seedMinuteCounters(human, 1_000);
        var jwt = jwtService.generateAccessToken(view(human));

        assertThat(get("Authorization", "Bearer " + jwt).statusCode()).isEqualTo(200);
        assertThat(get("X-API-Key", humanKey).statusCode()).isEqualTo(429);
    }

    private void perMinuteLimit(UserEntity user, int limit) {
        var account = serviceAccountRepository.findById(user.getId()).orElseThrow();
        account.setRateLimitPerMinute(limit);
        serviceAccountRepository.save(account);
    }

    private void seedMinuteCounters(UserEntity user, long count) {
        long minute = Instant.now().getEpochSecond() / 60;
        for (long m = minute; m <= minute + 1; m++) {
            redisTemplate.opsForValue().set(KEY_PREFIX + user.getId() + ":" + m, Long.toString(count),
                    Duration.ofMinutes(3));
        }
    }

    private HttpResponse<String> get(String header, String value) throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/me"))
                    .header("Accept", "application/json")
                    .header(header, value)
                    .GET()
                    .build();
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        }
    }

    private UserEntity saveUser(String email) {
        var u = new UserEntity();
        u.setId(UUID.randomUUID());
        u.setEmail(email);
        u.setDisplayName(email);
        u.setPasswordHash(passwordEncoder.encode(UUID.randomUUID().toString()));
        u.setRole(UserRoleType.ANALYST);
        u.setAuthProvider(AuthProviderType.LOCAL);
        u.setActive(true);
        u.setOrganization(org);
        return userRepository.save(u);
    }

    private static UserView view(UserEntity user) {
        return new UserView(user.getId(), user.getEmail(), user.getDisplayName(), user.getRole(),
                user.getOrganization().getId(), user.isActive(), user.getAuthProvider(), user.getPasswordHash(),
                user.getLastLoginAt(), user.getPreferredLanguage(), user.isTotpEnabled(), user.getCreatedAt());
    }
}
