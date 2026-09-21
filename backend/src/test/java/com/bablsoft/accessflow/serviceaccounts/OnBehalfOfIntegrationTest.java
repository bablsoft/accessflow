package com.bablsoft.accessflow.serviceaccounts;

import com.bablsoft.accessflow.TestcontainersConfig;
import com.bablsoft.accessflow.audit.api.AuditAction;
import com.bablsoft.accessflow.audit.api.AuditLogQuery;
import com.bablsoft.accessflow.audit.api.AuditLogService;
import com.bablsoft.accessflow.core.api.AuthProviderType;
import com.bablsoft.accessflow.core.api.PageRequest;
import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.core.api.UserView;
import com.bablsoft.accessflow.core.internal.persistence.entity.OrganizationEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.UserEntity;
import com.bablsoft.accessflow.core.internal.persistence.repo.OrganizationRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.UserRepository;
import com.bablsoft.accessflow.deploygov.api.CreateDeploymentEnvironmentCommand;
import com.bablsoft.accessflow.deploygov.api.CreateDeploymentPipelineCommand;
import com.bablsoft.accessflow.deploygov.api.DeploymentPermissionService;
import com.bablsoft.accessflow.deploygov.api.DeploymentPipelineAdminService;
import com.bablsoft.accessflow.deploygov.api.GrantDeploymentPermissionCommand;
import com.bablsoft.accessflow.deploygov.api.PipelineProvider;
import com.bablsoft.accessflow.security.api.ApiKeyService;
import com.bablsoft.accessflow.security.internal.jwt.JwtService;
import com.bablsoft.accessflow.serviceaccounts.api.ServiceAccountProvisioningService;
import com.bablsoft.accessflow.serviceaccounts.api.ServiceAccountSource;
import io.modelcontextprotocol.server.McpStatelessSyncServer;
import org.awaitility.Awaitility;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code X-AccessFlow-On-Behalf-Of} end to end (#874), over real HTTP: the header is resolved by
 * the order-0 {@code ApiKeyRequestFilter}, which MockMvc never runs. Same context as the other
 * {@code RANDOM_PORT} tests — no mocks, no test configuration — so it costs no extra Spring context.
 *
 * <p>The laundering case uses the deployment surface because its fixture is the smallest: the
 * bot triggers a deployment <em>for</em> Alice, Alice — an ADMIN, holding every permission — is
 * still refused the approval, a third party is not, and the audit trail names all three parties.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ImportTestcontainers(TestcontainersConfig.class)
class OnBehalfOfIntegrationTest {

    private static final String HEADER = "X-AccessFlow-On-Behalf-Of";
    private static final String API_KEY = "X-API-Key";
    private static final String RATE_LIMIT_PREFIX = "accessflow:serviceaccounts:ratelimit:";

    @LocalServerPort int port;
    @Autowired UserRepository userRepository;
    @Autowired OrganizationRepository organizationRepository;
    @Autowired ServiceAccountProvisioningService provisioningService;
    @Autowired ApiKeyService apiKeyService;
    @Autowired JwtService jwtService;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired DeploymentPipelineAdminService pipelineService;
    @Autowired DeploymentPermissionService permissionService;
    @Autowired AuditLogService auditLogService;
    @Autowired StringRedisTemplate redisTemplate;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired ObjectMapper objectMapper;
    // The test profile initializes beans lazily; the server bean is what registers the JSON-RPC
    // handler on the transport, so it has to exist before the first /mcp request.
    @Autowired McpStatelessSyncServer mcpServer;

    private OrganizationEntity org;
    private OrganizationEntity otherOrg;
    private UserEntity alice;
    private UserEntity bob;
    private UserEntity bot;
    private UserEntity stranger;
    private String aliceJwt;
    private String bobJwt;
    private String botKey;

    @BeforeEach
    void setUp() {
        var suffix = UUID.randomUUID().toString();
        org = saveOrg("obo-" + suffix);
        otherOrg = saveOrg("obo-other-" + suffix);
        alice = saveUser(org, "alice-" + suffix + "@example.com", UserRoleType.ADMIN);
        bob = saveUser(org, "bob-" + suffix + "@example.com", UserRoleType.REVIEWER);
        stranger = saveUser(otherOrg, "stranger-" + suffix + "@example.com", UserRoleType.ADMIN);
        bot = saveUser(org, "bot-" + suffix + "@example.com", UserRoleType.READONLY);
        provisioningService.ensureRegistered(org.getId(), bot.getId(), ServiceAccountSource.UI);
        botKey = apiKeyService.issue(bot.getId(), org.getId(), "bot", null).rawKey();
        aliceJwt = jwtService.generateAccessToken(view(alice));
        bobJwt = jwtService.generateAccessToken(view(bob));
    }

    @AfterEach
    void cleanup() {
        var keys = redisTemplate.keys(RATE_LIMIT_PREFIX + bot.getId() + ":*");
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
        for (var organization : List.of(org, otherOrg)) {
            // deployment_requests cascades its review decisions.
            jdbcTemplate.update("DELETE FROM deployment_requests WHERE organization_id = ?", organization.getId());
            jdbcTemplate.update("DELETE FROM deployment_pipelines WHERE organization_id = ?", organization.getId());
            jdbcTemplate.update("DELETE FROM audit_log WHERE organization_id = ?", organization.getId());
            // users cascades api_keys, service_accounts and service_account_delegated_principals.
            jdbcTemplate.update("DELETE FROM users WHERE organization_id = ?", organization.getId());
            jdbcTemplate.update("DELETE FROM organizations WHERE id = ?", organization.getId());
        }
    }

    // ---- the header on its own ----

    @Test
    void headerOnAJwtSessionIsRefusedLoudly() throws Exception {
        var response = send(get("/api/v1/me").header("Authorization", "Bearer " + aliceJwt)
                .header(HEADER, bob.getEmail()));

        assertThat(response.statusCode()).isEqualTo(403);
        var body = objectMapper.readTree(response.body());
        assertThat(body.get("error").asString()).isEqualTo("ON_BEHALF_OF_NOT_PERMITTED");
        assertThat(body.get("reason").asString()).isEqualTo("not_api_key");
    }

    @Test
    void unknownForeignUngrantedRevokedAndExpiredPrincipalsAreAll403() throws Exception {
        // unknown
        assertNotPermitted("ghost-" + UUID.randomUUID() + "@example.com");
        // another organization's human (an existing, active human — still opaque)
        assertNotPermitted(stranger.getEmail());
        // same organization, no grant
        assertNotPermitted(alice.getEmail());
        assertNotPermitted(alice.getId().toString());
        // service account as principal
        assertNotPermitted(bot.getId().toString());

        // revoked
        var revoked = grant(alice, null);
        assertThat(send(get("/api/v1/me").header(API_KEY, botKey).header(HEADER, alice.getEmail()))
                .statusCode()).isEqualTo(200);
        assertThat(send(delete("/api/v1/admin/service-accounts/" + bot.getId() + "/delegated-principals/"
                + revoked).header("Authorization", "Bearer " + aliceJwt)).statusCode()).isEqualTo(204);
        assertNotPermitted(alice.getEmail());

        // expired: grant with a future expiry, then age it in the database
        var expiring = grant(alice, Instant.now().plus(Duration.ofHours(1)));
        jdbcTemplate.update("UPDATE service_account_delegated_principals SET expires_at = ? WHERE id = ?",
                OffsetDateTime.ofInstant(Instant.now().minusSeconds(5), ZoneOffset.UTC), expiring);
        assertNotPermitted(alice.getEmail());
    }

    @Test
    void grantingToAServiceAccountOrAForeignHumanIs422AndADuplicateIs409() throws Exception {
        var otherBot = saveUser(org, "bot2-" + UUID.randomUUID() + "@example.com", UserRoleType.READONLY);
        provisioningService.ensureRegistered(org.getId(), otherBot.getId(), ServiceAccountSource.UI);

        assertThat(grantResponse(bot, otherBot.getId(), null).statusCode()).isEqualTo(422);
        assertThat(grantResponse(bot, stranger.getId(), null).statusCode()).isEqualTo(422);
        assertThat(grantResponse(bot, alice.getId(), Instant.now().minusSeconds(1)).statusCode()).isEqualTo(422);
        // a human "service account" is a 404, never a way to make a person act for a person
        assertThat(grantResponse(bob, alice.getId(), null).statusCode()).isEqualTo(404);

        assertThat(grantResponse(bot, alice.getId(), null).statusCode()).isEqualTo(201);
        var duplicate = grantResponse(bot, alice.getId(), null);
        assertThat(duplicate.statusCode()).isEqualTo(409);
        assertThat(objectMapper.readTree(duplicate.body()).get("error").asString())
                .isEqualTo("SERVICE_ACCOUNT_DELEGATION_EXISTS");
    }

    @Test
    void namingAHumanGrantsZeroAdditionalPermissions() throws Exception {
        grant(alice, null);

        // Alice is an ADMIN; the bot is READONLY. Naming Alice must not open an ADMIN endpoint...
        var admin = "/api/v1/admin/service-accounts";
        assertThat(send(get(admin).header(API_KEY, botKey)).statusCode()).isEqualTo(403);
        assertThat(send(get(admin).header(API_KEY, botKey).header(HEADER, alice.getEmail())).statusCode())
                .isEqualTo(403);
        // ...and the profile, permissions included, is the bot's either way.
        var without = objectMapper.readTree(send(get("/api/v1/me").header(API_KEY, botKey)).body());
        var with = objectMapper.readTree(send(get("/api/v1/me").header(API_KEY, botKey)
                .header(HEADER, alice.getEmail())).body());
        assertThat(with).isEqualTo(without);
        assertThat(with.get("id").asString()).isEqualTo(bot.getId().toString());
    }

    @Test
    void headerIsRefusedOnEveryReviewSurfaceBeforeAnyLookup() throws Exception {
        grant(alice, null);
        var id = UUID.randomUUID();
        for (var path : List.of(
                "/api/v1/reviews/" + id + "/approve",
                "/api/v1/api-reviews/" + id + "/approve",
                "/api/v1/deployment-reviews/" + id + "/approve",
                "/api/v1/deployment-rollback-reviews/" + id + "/acknowledge",
                "/api/v1/request-groups/" + id + "/approve",
                "/api/v1/admin/break-glass/" + id + "/acknowledge")) {
            var response = send(post(path, "{\"comment\":\"x\"}").header(API_KEY, botKey)
                    .header(HEADER, alice.getEmail()));
            assertThat(response.statusCode()).as(path).isEqualTo(403);
            assertThat(objectMapper.readTree(response.body()).get("error").asString()).as(path)
                    .isEqualTo("ON_BEHALF_OF_REVIEW_FORBIDDEN");
        }
        // A read on the review surface is refused too — the header is meaningless there.
        var list = send(get("/api/v1/reviews/pending").header(API_KEY, botKey).header(HEADER, alice.getEmail()));
        assertThat(list.statusCode()).isEqualTo(403);
        assertThat(objectMapper.readTree(list.body()).get("error").asString())
                .isEqualTo("ON_BEHALF_OF_REVIEW_FORBIDDEN");
    }

    @Test
    void mcpReviewQueryIsDeniedWhileSubmitToolsStayReachable() throws Exception {
        grant(alice, null);
        var call = """
                {"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"review_query","arguments":
                {"queryId":"%s","decision":"APPROVE"}}}
                """.formatted(UUID.randomUUID());

        var response = send(HttpRequest.newBuilder(uri("/mcp"))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .header(API_KEY, botKey)
                .header(HEADER, alice.getEmail())
                .POST(HttpRequest.BodyPublishers.ofString(call)));

        assertThat(response.statusCode()).isEqualTo(200);
        var body = objectMapper.readTree(response.body());
        var text = objectMapper.readTree(body.path("result").path("content").get(0).path("text").asString());
        assertThat(text.path("code").asString()).isEqualTo("permission_denied");
    }

    // ---- the laundering hole, end to end ----

    @Test
    void aliceCannotApproveTheDeploymentHerAgentSubmittedForHer() throws Exception {
        grant(alice, null);
        var pipelineId = pipelineTriggerableBy(bot);

        var submitted = send(post("/api/v1/deployment-requests", """
                {"pipeline_id":"%s","environment":"production","version":"2.4.1","commit_sha":"abc123",
                 "external_run_id":"run-%s","justification":"ship it"}
                """.formatted(pipelineId, UUID.randomUUID()))
                .header(API_KEY, botKey).header(HEADER, alice.getEmail()));
        assertThat(submitted.statusCode()).as(submitted.body()).isEqualTo(202);
        var request = objectMapper.readTree(submitted.body());
        var requestId = UUID.fromString(request.get("id").asString());
        assertThat(request.get("submitted_by").asString()).isEqualTo(bot.getId().toString());
        assertThat(request.get("on_behalf_of_user_id").asString()).isEqualTo(alice.getId().toString());
        assertThat(request.get("on_behalf_of_email").asString()).isEqualTo(alice.getEmail());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT on_behalf_of_user_id FROM deployment_requests WHERE id = ?", UUID.class, requestId))
                .isEqualTo(alice.getId());

        // The synchronous DEPLOYMENT_SUBMITTED row names all three parties through the contributor.
        var submittedRow = auditLogService.query(org.getId(), AuditLogQuery.empty(), PageRequest.of(0, 20))
                .content().stream()
                .filter(row -> row.action() == AuditAction.DEPLOYMENT_SUBMITTED)
                .findFirst().orElseThrow();
        assertThat(submittedRow.actorId()).isEqualTo(bot.getId());
        assertThat(submittedRow.metadata())
                .containsEntry("on_behalf_of_user_id", alice.getId().toString())
                .containsEntry("service_account", true)
                .containsKey("api_key_id");
        assertThat(auditLogService.verify(org.getId(), null, null).ok()).isTrue();

        Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(jdbcTemplate.queryForObject("SELECT status FROM deployment_requests WHERE id = ?",
                        String.class, requestId)).isEqualTo(QueryStatus.PENDING_REVIEW.name()));

        // Alice: ADMIN, every permission — still not eligible, and it is not in her queue.
        var aliceQueue = objectMapper.readTree(send(get("/api/v1/deployment-reviews")
                .header("Authorization", "Bearer " + aliceJwt)).body());
        assertThat(aliceQueue.path("content").findValues("deployment_request_id"))
                .extracting(JsonNode::asString).doesNotContain(requestId.toString());
        var aliceApproves = send(post("/api/v1/deployment-reviews/" + requestId + "/approve",
                "{\"comment\":\"self\"}").header("Authorization", "Bearer " + aliceJwt));
        assertThat(aliceApproves.statusCode()).isEqualTo(409);
        assertThat(objectMapper.readTree(aliceApproves.body()).get("error").asString())
                .isEqualTo("DEPLOYMENT_REQUEST_SELF_APPROVAL");

        // Bob, a third party, may.
        var bobApproves = send(post("/api/v1/deployment-reviews/" + requestId + "/approve",
                "{\"comment\":\"lgtm\"}").header("Authorization", "Bearer " + bobJwt));
        assertThat(bobApproves.statusCode()).as(bobApproves.body()).isEqualTo(200);
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM deployment_requests WHERE id = ?",
                String.class, requestId)).isEqualTo(QueryStatus.APPROVED.name());
    }

    @Test
    void selfServiceGrantListAndRevokeAndAServiceAccountCannotBeAPrincipal() throws Exception {
        var granted = send(post("/api/v1/me/service-account-delegations",
                "{\"service_account_user_id\":\"" + bot.getId() + "\"}")
                .header("Authorization", "Bearer " + aliceJwt));
        assertThat(granted.statusCode()).as(granted.body()).isEqualTo(201);
        var grantId = objectMapper.readTree(granted.body()).get("id").asString();

        var listed = objectMapper.readTree(send(get("/api/v1/me/service-account-delegations")
                .header("Authorization", "Bearer " + aliceJwt)).body());
        assertThat(listed.findValues("id")).extracting(JsonNode::asString).contains(grantId);
        assertThat(send(get("/api/v1/me").header(API_KEY, botKey).header(HEADER, alice.getId().toString()))
                .statusCode()).isEqualTo(200);

        // Bob cannot revoke Alice's grant through the self-service surface.
        assertThat(send(delete("/api/v1/me/service-account-delegations/" + grantId)
                .header("Authorization", "Bearer " + bobJwt)).statusCode()).isEqualTo(404);
        assertThat(send(delete("/api/v1/me/service-account-delegations/" + grantId)
                .header("Authorization", "Bearer " + aliceJwt)).statusCode()).isEqualTo(204);
        assertNotPermitted(alice.getEmail());

        // The bot, with its own key, is itself the principal on this surface — never valid.
        var botGrants = send(post("/api/v1/me/service-account-delegations",
                "{\"service_account_user_id\":\"" + bot.getId() + "\"}").header(API_KEY, botKey));
        assertThat(botGrants.statusCode()).isEqualTo(422);
    }

    // ---- helpers ----

    private void assertNotPermitted(String reference) throws Exception {
        var response = send(get("/api/v1/me").header(API_KEY, botKey).header(HEADER, reference));
        assertThat(response.statusCode()).as(reference).isEqualTo(403);
        var body = objectMapper.readTree(response.body());
        assertThat(body.get("error").asString()).isEqualTo("ON_BEHALF_OF_NOT_PERMITTED");
        assertThat(body.get("reason").asString()).isEqualTo("not_permitted");
    }

    private UUID grant(UserEntity principal, Instant expiresAt) throws Exception {
        var response = grantResponse(bot, principal.getId(), expiresAt);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        return UUID.fromString(objectMapper.readTree(response.body()).get("id").asString());
    }

    private HttpResponse<String> grantResponse(UserEntity account, UUID principalId, Instant expiresAt)
            throws Exception {
        var body = expiresAt == null
                ? "{\"principal_user_id\":\"" + principalId + "\"}"
                : "{\"principal_user_id\":\"" + principalId + "\",\"expires_at\":\"" + expiresAt + "\"}";
        return send(post("/api/v1/admin/service-accounts/" + account.getId() + "/delegated-principals", body)
                .header("Authorization", "Bearer " + aliceJwt));
    }

    private UUID pipelineTriggerableBy(UserEntity user) {
        // AI analysis off so routing lands straight in PENDING_REVIEW; one approval, no review plan,
        // so any DEPLOYMENT_REVIEW holder is eligible.
        var pipeline = pipelineService.create(new CreateDeploymentPipelineCommand(org.getId(),
                "payments-" + UUID.randomUUID(), PipelineProvider.GITHUB_ACTIONS, null, null, null, false, null));
        pipelineService.createEnvironment(pipeline.id(), org.getId(),
                new CreateDeploymentEnvironmentCommand("production", 1, true, 1, null, false, null, null));
        permissionService.grantPermission(pipeline.id(), org.getId(), user.getId(),
                new GrantDeploymentPermissionCommand(user.getId(), true, false, null));
        return pipeline.id();
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private HttpRequest.Builder get(String path) {
        return HttpRequest.newBuilder(uri(path)).header("Accept", "application/json").GET();
    }

    private HttpRequest.Builder delete(String path) {
        return HttpRequest.newBuilder(uri(path)).header("Accept", "application/json").DELETE();
    }

    private HttpRequest.Builder post(String path, String body) {
        return HttpRequest.newBuilder(uri(path))
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
    }

    private static HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    private OrganizationEntity saveOrg(String slug) {
        var organization = new OrganizationEntity();
        organization.setId(UUID.randomUUID());
        organization.setName(slug);
        organization.setSlug(slug);
        return organizationRepository.save(organization);
    }

    private UserEntity saveUser(OrganizationEntity organization, String email, UserRoleType role) {
        var user = new UserEntity();
        user.setId(UUID.randomUUID());
        user.setEmail(email);
        user.setDisplayName(email);
        user.setPasswordHash(passwordEncoder.encode(UUID.randomUUID().toString()));
        user.setRole(role);
        user.setAuthProvider(AuthProviderType.LOCAL);
        user.setActive(true);
        user.setOrganization(organization);
        return userRepository.save(user);
    }

    private static UserView view(UserEntity user) {
        return new UserView(user.getId(), user.getEmail(), user.getDisplayName(), user.getRole(),
                user.getOrganization().getId(), user.isActive(), user.getAuthProvider(), user.getPasswordHash(),
                user.getLastLoginAt(), user.getPreferredLanguage(), user.isTotpEnabled(), user.getCreatedAt());
    }
}
