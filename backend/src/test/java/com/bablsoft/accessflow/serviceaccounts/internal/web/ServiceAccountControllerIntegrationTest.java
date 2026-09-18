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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.web.context.WebApplicationContext;

import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

/**
 * Drives {@code /api/v1/admin/service-accounts} against the real stack (#871): the CRUD round trip,
 * the bootstrap coexistence rules on one and the same account, grace-window rotation observed
 * through the API-key filter, and the declared-key revoke refusal on both surfaces.
 */
@SpringBootTest
@ImportTestcontainers(TestcontainersConfig.class)
class ServiceAccountControllerIntegrationTest {

    private static final String BASE = "/api/v1/admin/service-accounts";
    private static final String API_KEY_HEADER = "X-API-Key";

    @Autowired WebApplicationContext context;
    @Autowired UserRepository userRepository;
    @Autowired OrganizationRepository organizationRepository;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired JwtService jwtService;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired ApiKeyService apiKeyService;
    @Autowired ServiceAccountProvisioningService provisioningService;

    private MockMvcTester mvc;
    private OrganizationEntity org;
    private UserEntity admin;
    private String adminToken;
    private String analystToken;
    private String suffix;

    @BeforeEach
    void setUp() {
        mvc = MockMvcTester.from(context, b -> b.apply(springSecurity()).build());
        suffix = UUID.randomUUID().toString();
        org = new OrganizationEntity();
        org.setId(UUID.randomUUID());
        org.setName("sa-admin-" + suffix);
        org.setSlug("sa-admin-" + suffix);
        organizationRepository.save(org);
        admin = saveUser("admin-" + suffix + "@example.com", UserRoleType.ADMIN);
        adminToken = jwtService.generateAccessToken(view(admin));
        analystToken = jwtService.generateAccessToken(view(saveUser("analyst-" + suffix + "@example.com",
                UserRoleType.ANALYST)));
    }

    @AfterEach
    void cleanup() {
        // users cascades api_keys and service_accounts; audit_log rows are org-scoped.
        jdbcTemplate.update("DELETE FROM audit_log WHERE organization_id = ?", org.getId());
        jdbcTemplate.update("DELETE FROM users WHERE organization_id = ?", org.getId());
        jdbcTemplate.update("DELETE FROM organizations WHERE id = ?", org.getId());
    }

    // ---- CRUD -------------------------------------------------------------------------------

    @Test
    void createGetListUpdateDeactivateRoundTrip() {
        var email = "bot-" + suffix + "@example.com";
        var create = mvc.post().uri(BASE).header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email":"%s","display_name":"Nightly bot","description":"reports",
                         "owner_user_id":"%s","mcp_tool_allow_list":["list_datasources","validate_sql"],
                         "rate_limit_per_minute":60}
                        """.formatted(email, admin.getId()))
                .exchange();
        assertThat(create).hasStatus(201);
        assertThat(create.getResponse().getHeader(HttpHeaders.LOCATION)).contains(BASE + "/");
        assertThat(create).bodyJson().extractingPath("$.role").asString().isEqualTo("READONLY");
        assertThat(create).bodyJson().extractingPath("$.managed_by").asString().isEqualTo("UI");
        assertThat(create).bodyJson().extractingPath("$.active").asBoolean().isTrue();
        assertThat(create).bodyJson().extractingPath("$.mcp_tool_allow_list").asArray()
                .containsExactly("list_datasources", "validate_sql");
        assertThat(create).bodyJson().extractingPath("$.active_api_key_count").asNumber().isEqualTo(0);
        var id = UUID.fromString(create.getResponse().getHeader(HttpHeaders.LOCATION)
                .replaceAll(".*/", ""));

        assertThat(jdbcTemplate.queryForObject("SELECT principal_type::text FROM users WHERE id = ?",
                String.class, id)).isEqualTo("SERVICE_ACCOUNT");
        assertThat(jdbcTemplate.queryForObject("SELECT managed_by::text FROM service_accounts WHERE user_id = ?",
                String.class, id)).isEqualTo("UI");

        var get = mvc.get().uri(BASE + "/" + id).header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .exchange();
        assertThat(get).hasStatus(200);
        assertThat(get).bodyJson().extractingPath("$.email").asString().isEqualTo(email);
        assertThat(get).bodyJson().extractingPath("$.owner_user_id").asString().isEqualTo(admin.getId().toString());
        assertThat(get).bodyJson().extractingPath("$.api_keys").asArray().isEmpty();

        var list = mvc.get().uri(BASE + "?managed_by=UI").header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .exchange();
        assertThat(list).hasStatus(200);
        assertThat(list).bodyJson().extractingPath("$.content[0].id").asString().isEqualTo(id.toString());
        assertThat(list).bodyJson().extractingPath("$.content[0].api_keys").asArray().isEmpty();
        var filtered = mvc.get().uri(BASE + "?managed_by=BOOTSTRAP")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken).exchange();
        assertThat(filtered).bodyJson().extractingPath("$.content").asArray().isEmpty();

        var update = mvc.put().uri(BASE + "/" + id).header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"display_name":"Renamed","role":"ANALYST","mcp_tool_allow_list":[],
                         "rate_limit_per_day":500,"clear":["OWNER_USER_ID"]}
                        """)
                .exchange();
        assertThat(update).hasStatus(200);
        assertThat(update).bodyJson().extractingPath("$.display_name").asString().isEqualTo("Renamed");
        assertThat(update).bodyJson().extractingPath("$.role").asString().isEqualTo("ANALYST");
        assertThat(update).bodyJson().extractingPath("$.mcp_tool_allow_list").asArray().isEmpty();
        assertThat(update).bodyJson().extractingPath("$.rate_limit_per_day").asNumber().isEqualTo(500);
        assertThat(update).bodyJson().doesNotHavePath("$.owner_user_id");
        // Omitted = unchanged.
        assertThat(update).bodyJson().extractingPath("$.description").asString().isEqualTo("reports");
        assertThat(update).bodyJson().extractingPath("$.rate_limit_per_minute").asNumber().isEqualTo(60);

        var conflict = mvc.put().uri(BASE + "/" + id).header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"description\":\"x\",\"clear\":[\"DESCRIPTION\"]}").exchange();
        assertThat(conflict).hasStatus(400);

        var deactivate = mvc.delete().uri(BASE + "/" + id).header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .exchange();
        assertThat(deactivate).hasStatus(204);
        assertThat(mvc.get().uri(BASE + "/" + id).header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .exchange()).bodyJson().extractingPath("$.active").asBoolean().isFalse();

        assertThat(auditRows("SERVICE_ACCOUNT_CREATED")).isEqualTo(1);
        assertThat(auditRows("SERVICE_ACCOUNT_UPDATED")).isEqualTo(1);
        assertThat(auditRows("SERVICE_ACCOUNT_DEACTIVATED")).isEqualTo(1);
    }

    @Test
    void validationOwnerAndToolErrorsAreDistinct() {
        var blank = mvc.post().uri(BASE).header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"not-an-email\",\"display_name\":\"\"}").exchange();
        assertThat(blank).hasStatus(400);
        assertThat(blank).bodyJson().extractingPath("$.error").asString().isEqualTo("VALIDATION_ERROR");

        var badEnum = mvc.post().uri(BASE).header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"e-%s@example.com\",\"display_name\":\"x\",\"role\":\"ROBOT\"}"
                        .formatted(suffix)).exchange();
        assertThat(badEnum).hasStatus(400);
        assertThat(badEnum).bodyJson().extractingPath("$.error").asString().isEqualTo("VALIDATION_ERROR");
        var badFilter = mvc.get().uri(BASE + "?managed_by=YAML")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken).exchange();
        assertThat(badFilter).hasStatus(400);

        var badOwner = mvc.post().uri(BASE).header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"o-%s@example.com\",\"display_name\":\"x\",\"owner_user_id\":\"%s\"}"
                        .formatted(suffix, UUID.randomUUID())).exchange();
        assertThat(badOwner).hasStatus(422);
        assertThat(badOwner).bodyJson().extractingPath("$.error").asString().isEqualTo("SERVICE_ACCOUNT_OWNER_INVALID");

        var badTool = mvc.post().uri(BASE).header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"t-%s@example.com\",\"display_name\":\"x\",\"mcp_tool_allow_list\":[\"nope\"]}"
                        .formatted(suffix)).exchange();
        assertThat(badTool).hasStatus(422);
        assertThat(badTool).bodyJson().extractingPath("$.error").asString().isEqualTo("SERVICE_ACCOUNT_UNKNOWN_MCP_TOOL");
        assertThat(badTool).bodyJson().extractingPath("$.tool").asString().isEqualTo("nope");

        var duplicate = mvc.post().uri(BASE).header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\",\"display_name\":\"x\"}".formatted(admin.getEmail())).exchange();
        assertThat(duplicate).hasStatus(409);
        assertThat(duplicate).bodyJson().extractingPath("$.error").asString().isEqualTo("EMAIL_ALREADY_EXISTS");
    }

    @Test
    void analystIsForbiddenAndAHumanOrForeignIdIsNotFound() {
        assertThat(mvc.get().uri(BASE).header(HttpHeaders.AUTHORIZATION, "Bearer " + analystToken).exchange())
                .hasStatus(403);
        var human = mvc.get().uri(BASE + "/" + admin.getId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken).exchange();
        assertThat(human).hasStatus(404);
        assertThat(human).bodyJson().extractingPath("$.error").asString().isEqualTo("SERVICE_ACCOUNT_NOT_FOUND");
        assertThat(mvc.get().uri(BASE + "/" + UUID.randomUUID())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken).exchange()).hasStatus(404);
    }

    // ---- bootstrap coexistence --------------------------------------------------------------

    @Test
    void aBootstrapDeclaredFieldIs409WhileAUiOwnedFieldOnTheSameAccountSucceeds() {
        var botId = seedBootstrapAccount().userId();

        var declared = mvc.put().uri(BASE + "/" + botId).header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"display_name\":\"Not the YAML name\"}").exchange();
        assertThat(declared).hasStatus(409);
        assertThat(declared).bodyJson().extractingPath("$.error").asString().isEqualTo("SERVICE_ACCOUNT_BOOTSTRAP_MANAGED");
        assertThat(declared).bodyJson().extractingPath("$.field").asString().isEqualTo("display_name");

        var role = mvc.put().uri(BASE + "/" + botId).header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"role\":\"ADMIN\"}").exchange();
        assertThat(role).hasStatus(409);
        assertThat(role).bodyJson().extractingPath("$.field").asString().isEqualTo("role");

        var uiOwned = mvc.put().uri(BASE + "/" + botId).header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"display_name":"CI runner","role":"REVIEWER","description":"edited in the UI",
                         "mcp_tool_allow_list":["validate_sql"],"rate_limit_per_minute":10}
                        """).exchange();
        assertThat(uiOwned).hasStatus(200);
        assertThat(uiOwned).bodyJson().extractingPath("$.description").asString().isEqualTo("edited in the UI");
        assertThat(uiOwned).bodyJson().extractingPath("$.managed_by").asString().isEqualTo("BOOTSTRAP");
        assertThat(jdbcTemplate.queryForObject("SELECT description FROM service_accounts WHERE user_id = ?",
                String.class, botId)).isEqualTo("edited in the UI");
        assertThat(jdbcTemplate.queryForObject("SELECT display_name FROM users WHERE id = ?",
                String.class, botId)).isEqualTo("CI runner");
    }

    @Test
    void revokingTheDeclaredKeyIsRefusedOnBothSurfaces() {
        var seeded = seedBootstrapAccount();

        var admin409 = mvc.delete().uri(BASE + "/" + seeded.userId() + "/api-keys/" + seeded.keyId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken).exchange();
        assertThat(admin409).hasStatus(409);
        assertThat(admin409).bodyJson().extractingPath("$.error").asString()
                .isEqualTo("SERVICE_ACCOUNT_KEY_BOOTSTRAP_DECLARED");
        assertThat(admin409).bodyJson().extractingPath("$.detail").asString().contains("bootstrap");

        var rotate409 = mvc.post().uri(BASE + "/" + seeded.userId() + "/api-keys/" + seeded.keyId() + "/rotate")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"x\"}").exchange();
        assertThat(rotate409).hasStatus(409);
        assertThat(rotate409).bodyJson().extractingPath("$.error").asString()
                .isEqualTo("SERVICE_ACCOUNT_KEY_BOOTSTRAP_DECLARED");

        // The account itself, authenticating with that very key, cannot self-revoke it either.
        var self409 = mvc.delete().uri("/api/v1/me/api-keys/" + seeded.keyId())
                .header(API_KEY_HEADER, seeded.rawKey()).exchange();
        assertThat(self409).hasStatus(409);
        assertThat(self409).bodyJson().extractingPath("$.error").asString().isEqualTo("API_KEY_BOOTSTRAP_DECLARED");

        assertThat(jdbcTemplate.queryForObject("SELECT revoked_at FROM api_keys WHERE id = ?",
                OffsetDateTime.class, seeded.keyId())).isNull();
        assertThat(auditRows("SERVICE_ACCOUNT_KEY_REVOKED")).isZero();

        // An additional, undeclared key on the same bootstrap account is fully manageable.
        var extra = mvc.post().uri(BASE + "/" + seeded.userId() + "/api-keys")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"extra\"}").exchange();
        assertThat(extra).hasStatus(201);
        assertThat(extra).bodyJson().extractingPath("$.api_key.bootstrap_declared").asBoolean().isFalse();
        var extraId = text(extra).replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");
        assertThat(mvc.delete().uri(BASE + "/" + seeded.userId() + "/api-keys/" + extraId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken).exchange()).hasStatus(204);
    }

    // ---- rotation ---------------------------------------------------------------------------

    @Test
    void rotationKeepsTheOldKeyAuthenticatingUntilTheGraceElapses() {
        var botId = createUiAccount();
        var issued = mvc.post().uri(BASE + "/" + botId + "/api-keys")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"ci\"}").exchange();
        assertThat(issued).hasStatus(201);
        var body = text(issued);
        var oldRaw = body.replaceAll(".*\"raw_key\":\"([^\"]+)\".*", "$1");
        var oldId = body.replaceAll(".*\"api_key\":\\{\"id\":\"([^\"]+)\".*", "$1");
        assertThat(mvc.get().uri("/api/v1/me").header(API_KEY_HEADER, oldRaw).exchange()).hasStatus(200);

        var before = Instant.now();
        var rotated = mvc.post().uri(BASE + "/" + botId + "/api-keys/" + oldId + "/rotate")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"ci-rotated\",\"grace_period\":\"PT1S\"}").exchange();
        assertThat(rotated).hasStatus(201);
        assertThat(rotated).bodyJson().extractingPath("$.api_key.name").asString().isEqualTo("ci-rotated");
        assertThat(rotated).bodyJson().extractingPath("$.superseded_key.id").asString().isEqualTo(oldId);
        assertThat(rotated).bodyJson().doesNotHavePath("$.superseded_key.revoked_at");
        var newRaw = text(rotated).replaceAll(".*\"raw_key\":\"([^\"]+)\".*", "$1");

        // Expiry, not revocation: the old row is untouched except for expires_at = now + grace.
        assertThat(jdbcTemplate.queryForObject("SELECT revoked_at FROM api_keys WHERE id = ?::uuid",
                OffsetDateTime.class, oldId)).isNull();
        var expiresAt = jdbcTemplate.queryForObject("SELECT expires_at FROM api_keys WHERE id = ?::uuid",
                OffsetDateTime.class, oldId).toInstant();
        assertThat(expiresAt).isCloseTo(before.plusSeconds(1), within(2, java.time.temporal.ChronoUnit.SECONDS));

        // The replacement works at once; the old key keeps working until the window elapses.
        assertThat(mvc.get().uri("/api/v1/me").header(API_KEY_HEADER, newRaw).exchange()).hasStatus(200);
        await().atMost(Duration.ofSeconds(5)).pollInterval(Duration.ofMillis(200)).untilAsserted(() ->
                assertThat(mvc.get().uri("/api/v1/me").header(API_KEY_HEADER, oldRaw).exchange()).hasStatus(401));
        assertThat(mvc.get().uri("/api/v1/me").header(API_KEY_HEADER, newRaw).exchange()).hasStatus(200);

        assertThat(auditRows("SERVICE_ACCOUNT_KEY_ISSUED")).isEqualTo(1);
        assertThat(auditRows("SERVICE_ACCOUNT_KEY_ROTATED")).isEqualTo(1);
        assertThat(jdbcTemplate.queryForList("SELECT metadata::text FROM audit_log WHERE organization_id = ? "
                        + "AND action IN ('SERVICE_ACCOUNT_KEY_ISSUED', 'SERVICE_ACCOUNT_KEY_ROTATED')",
                String.class, org.getId()))
                .noneMatch(metadata -> metadata.contains(oldRaw) || metadata.contains(newRaw));

        var nonPositiveGrace = mvc.post().uri(BASE + "/" + botId + "/api-keys/" + oldId + "/rotate")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"again\",\"grace_period\":\"PT0S\"}").exchange();
        assertThat(nonPositiveGrace).hasStatus(400);
    }

    @Test
    void deactivationStopsTheKeysWithoutRevokingThemAndReactivationRestoresThem() {
        var botId = createUiAccount();
        var issued = mvc.post().uri(BASE + "/" + botId + "/api-keys")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"ci\"}").exchange();
        var raw = text(issued).replaceAll(".*\"raw_key\":\"([^\"]+)\".*", "$1");
        assertThat(mvc.get().uri("/api/v1/me").header(API_KEY_HEADER, raw).exchange()).hasStatus(200);

        assertThat(mvc.delete().uri(BASE + "/" + botId).header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .exchange()).hasStatus(204);
        assertThat(mvc.get().uri("/api/v1/me").header(API_KEY_HEADER, raw).exchange()).hasStatus(401);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM api_keys WHERE user_id = ? AND revoked_at IS NULL",
                Integer.class, botId)).isEqualTo(1);

        // The documented remediation — and nothing else moves: an omitted allow-list stays restricted.
        var reactivate = mvc.put().uri(BASE + "/" + botId).header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON).content("{\"active\":true}").exchange();
        assertThat(reactivate).hasStatus(200);
        assertThat(reactivate).bodyJson().extractingPath("$.mcp_tool_allow_list").asArray()
                .containsExactly("validate_sql");
        assertThat(reactivate).bodyJson().extractingPath("$.rate_limit_per_minute").asNumber().isEqualTo(3);
        assertThat(mvc.get().uri("/api/v1/me").header(API_KEY_HEADER, raw).exchange()).hasStatus(200);
    }

    // ---- helpers ----------------------------------------------------------------------------

    private record Seeded(UUID userId, UUID keyId, String rawKey) {
    }

    /** Exactly the reconciler's create path at api level: unusable hash, ensureRegistered(BOOTSTRAP), import. */
    private Seeded seedBootstrapAccount() {
        var bot = saveUser("ci-" + suffix + "@example.com", UserRoleType.REVIEWER);
        bot.setDisplayName("CI runner");
        userRepository.save(bot);
        provisioningService.ensureRegistered(org.getId(), bot.getId(), ServiceAccountSource.BOOTSTRAP);
        var rawKey = "af_" + suffix.replace("-", "") + "declaredkey";
        var imported = apiKeyService.importOrUpdate(bot.getId(), org.getId(), "terraform", rawKey, null);
        return new Seeded(bot.getId(), imported.id(), rawKey);
    }

    private UUID createUiAccount() {
        var create = mvc.post().uri(BASE).header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"ui-%s@example.com\",\"display_name\":\"UI bot\","
                        + "\"mcp_tool_allow_list\":[\"validate_sql\"],\"rate_limit_per_minute\":3}".formatted(suffix))
                .exchange();
        assertThat(create).hasStatus(201);
        return UUID.fromString(create.getResponse().getHeader(HttpHeaders.LOCATION).replaceAll(".*/", ""));
    }

    private static String text(MvcTestResult result) {
        try {
            return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        } catch (UnsupportedEncodingException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private int auditRows(String action) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM audit_log WHERE organization_id = ? AND action = ?",
                Integer.class, org.getId(), action);
    }

    private UserEntity saveUser(String email, UserRoleType role) {
        var u = new UserEntity();
        u.setId(UUID.randomUUID());
        u.setEmail(email);
        u.setDisplayName(email);
        u.setPasswordHash(passwordEncoder.encode(UUID.randomUUID().toString()));
        u.setRole(role);
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
