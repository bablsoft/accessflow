package com.bablsoft.accessflow.notifications.internal.web;

import com.bablsoft.accessflow.TestcontainersConfig;
import com.bablsoft.accessflow.core.api.AuthProviderType;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.core.internal.persistence.entity.OrganizationEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.UserEntity;
import com.bablsoft.accessflow.core.internal.persistence.repo.OrganizationRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.UserRepository;
import com.bablsoft.accessflow.notifications.api.SlackAppConfigService;
import com.bablsoft.accessflow.notifications.api.UpsertSlackAppConfigCommand;
import com.bablsoft.accessflow.notifications.internal.persistence.repo.SlackAppConfigRepository;
import com.bablsoft.accessflow.notifications.internal.persistence.repo.UserSlackMappingRepository;
import com.bablsoft.accessflow.security.internal.jwt.JwtService;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.web.context.WebApplicationContext;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.InetSocketAddress;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateCrtKey;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

@SpringBootTest
@ImportTestcontainers(TestcontainersConfig.class)
class SlackIntegrationControllerIntegrationTest {

    private static final String SECRET = "integration-signing-secret";
    private static final String APP_ID = "A12345";

    @Autowired WebApplicationContext context;
    @Autowired UserRepository userRepository;
    @Autowired OrganizationRepository organizationRepository;
    @Autowired SlackAppConfigRepository slackAppConfigRepository;
    @Autowired UserSlackMappingRepository userSlackMappingRepository;
    @Autowired SlackAppConfigService slackAppConfigService;
    @Autowired JwtService jwtService;

    private static final AtomicReference<HttpServer> SERVER = new AtomicReference<>();
    private static final ConcurrentLinkedQueue<String> RECEIVED = new ConcurrentLinkedQueue<>();
    private static int serverPort;

    private MockMvcTester mvc;
    private OrganizationEntity org;
    private UserEntity admin;
    private UserEntity analyst;
    private String adminToken;
    private String analystToken;

    @DynamicPropertySource
    static void env(DynamicPropertyRegistry registry) throws Exception {
        var kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        var kp = kpg.generateKeyPair();
        var privateKey = (RSAPrivateCrtKey) kp.getPrivate();
        var pem = "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(privateKey.getEncoded())
                + "\n-----END PRIVATE KEY-----";
        registry.add("accessflow.jwt.private-key", () -> pem);
        registry.add("accessflow.encryption-key", () ->
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef");
    }

    @BeforeAll
    static void startServer() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            RECEIVED.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        SERVER.set(server);
        serverPort = server.getAddress().getPort();
    }

    @AfterAll
    static void stopServer() {
        var s = SERVER.get();
        if (s != null) {
            s.stop(0);
        }
    }

    @BeforeEach
    void setUp() {
        mvc = MockMvcTester.from(context, builder -> builder.apply(springSecurity()).build());
        RECEIVED.clear();
        userSlackMappingRepository.deleteAll();
        slackAppConfigRepository.deleteAll();
        userRepository.deleteAll();
        organizationRepository.deleteAll();

        org = new OrganizationEntity();
        org.setId(UUID.randomUUID());
        org.setName("Primary");
        org.setSlug("primary-" + UUID.randomUUID());
        organizationRepository.save(org);

        admin = saveUser("admin@example.com", UserRoleType.ADMIN);
        analyst = saveUser("analyst@example.com", UserRoleType.ANALYST);
        adminToken = generateToken(admin);
        analystToken = generateToken(analyst);
    }

    @AfterEach
    void tearDown() {
        // Drop our rows so other integration tests' organizationRepository.deleteAll() doesn't trip
        // the slack_app_config / user_slack_mapping foreign keys to organizations.
        userSlackMappingRepository.deleteAll();
        slackAppConfigRepository.deleteAll();
    }

    private void seedApp() {
        slackAppConfigService.upsert(org.getId(),
                new UpsertSlackAppConfigCommand(APP_ID, "C-default", "xoxb-token", SECRET, true));
    }

    // ── Admin config ──────────────────────────────────────────────────────────

    @Test
    void adminUpsertCreatesAndMasksSecrets() throws Exception {
        var body = "{\"app_id\":\"" + APP_ID + "\",\"default_channel_id\":\"C1\","
                + "\"bot_token\":\"xoxb-secret\",\"signing_secret\":\"shh\",\"active\":true}";
        var created = mvc.put().uri("/api/v1/admin/slack-app-config")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON).content(body).exchange();

        assertThat(created).hasStatus(200);
        assertThat(created).bodyJson().extractingPath("$.has_bot_token").isEqualTo(true);
        assertThat(created.getResponse().getContentAsString())
                .doesNotContain("xoxb-secret").doesNotContain("shh");
    }

    @Test
    void adminUpsertRejectsMissingBotTokenOnCreate() {
        var body = "{\"app_id\":\"" + APP_ID + "\",\"default_channel_id\":\"C1\",\"active\":true}";
        var result = mvc.put().uri("/api/v1/admin/slack-app-config")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON).content(body).exchange();

        assertThat(result).hasStatus(422);
        assertThat(result).bodyJson().extractingPath("$.error").asString()
                .isEqualTo("SLACK_APP_CONFIG_INVALID");
    }

    @Test
    void adminGetReturns404WhenUnconfigured() {
        var result = mvc.get().uri("/api/v1/admin/slack-app-config")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken).exchange();
        assertThat(result).hasStatus(404);
    }

    @Test
    void adminEndpointForbiddenForAnalyst() {
        var result = mvc.get().uri("/api/v1/admin/slack-app-config")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + analystToken).exchange();
        assertThat(result).hasStatus(403);
    }

    // ── Inbound actions: signature / replay / unknown user ──────────────────────

    @Test
    void actionsRejectsWrongSignature() {
        seedApp();
        var body = actionsBody(UUID.randomUUID());
        var result = postActions(body, nowTs(), "v0=deadbeef");
        assertThat(result).hasStatus(401);
    }

    @Test
    void actionsRejectsStaleTimestamp() {
        seedApp();
        var body = actionsBody(UUID.randomUUID());
        var staleTs = Long.toString(Instant.now().minusSeconds(600).getEpochSecond());
        var result = postActions(body, staleTs, sign(staleTs, body));
        assertThat(result).hasStatus(401);
    }

    @Test
    void actionsRejectsMissingSignatureHeader() {
        seedApp();
        var body = actionsBody(UUID.randomUUID());
        var result = mvc.post().uri("/api/v1/integrations/slack/actions")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED).content(body).exchange();
        assertThat(result).hasStatus(401);
    }

    @Test
    void actionsValidSignatureUnknownUserReturns200AndEphemeral() {
        seedApp();
        var body = actionsBody(UUID.randomUUID());
        var ts = nowTs();
        var result = postActions(body, ts, sign(ts, body));

        assertThat(result).hasStatus(200);
        assertThat(RECEIVED).anyMatch(b -> b.contains("ephemeral") && b.toLowerCase().contains("not linked"));
    }

    @Test
    void actionsRejectsReplayedSignature() {
        seedApp();
        var body = actionsBody(UUID.randomUUID());
        var ts = nowTs();
        var sig = sign(ts, body);

        assertThat(postActions(body, ts, sig)).hasStatus(200);
        assertThat(postActions(body, ts, sig)).hasStatus(401);
    }

    // ── Slash command: link flow ────────────────────────────────────────────────

    @Test
    void commandWithInvalidCodeReturnsEphemeralError() {
        seedApp();
        var body = commandBody("link NONEXISTENT");
        var ts = nowTs();
        var result = postCommand(body, ts, sign(ts, body));

        assertThat(result).hasStatus(200);
        assertThat(result).bodyJson().extractingPath("$.response_type").asString().isEqualTo("ephemeral");
    }

    @Test
    void linkCodeFlowPersistsMapping() throws Exception {
        seedApp();
        var codeResponse = mvc.post().uri("/api/v1/integrations/slack/link-codes")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken).exchange();
        assertThat(codeResponse).hasStatus(201);
        var code = codeResponse.getResponse().getContentAsString()
                .replaceAll(".*\"code\":\"([^\"]+)\".*", "$1");

        var body = commandBody("link " + code);
        var ts = nowTs();
        var result = postCommand(body, ts, sign(ts, body));

        assertThat(result).hasStatus(200);
        var mapping = userSlackMappingRepository.findByUserId(admin.getId()).orElseThrow();
        assertThat(mapping.getSlackUserId()).isEqualTo("U-SLACK-1");
    }

    @Test
    void linkCodeEndpointRequiresAuthentication() {
        var result = mvc.post().uri("/api/v1/integrations/slack/link-codes").exchange();
        assertThat(result.getResponse().getStatus()).isIn(401, 403);
    }

    // ── helpers ─────────────────────────────────────────────────────────────────

    private org.springframework.test.web.servlet.assertj.MvcTestResult postActions(
            String body, String ts, String sig) {
        return mvc.post().uri("/api/v1/integrations/slack/actions")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .header("X-Slack-Request-Timestamp", ts)
                .header("X-Slack-Signature", sig)
                .content(body).exchange();
    }

    private org.springframework.test.web.servlet.assertj.MvcTestResult postCommand(
            String body, String ts, String sig) {
        return mvc.post().uri("/api/v1/integrations/slack/commands")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .header("X-Slack-Request-Timestamp", ts)
                .header("X-Slack-Signature", sig)
                .content(body).exchange();
    }

    private String actionsBody(UUID queryId) {
        var json = "{\"api_app_id\":\"" + APP_ID + "\",\"user\":{\"id\":\"U-SLACK-1\"},"
                + "\"actions\":[{\"action_id\":\"approve\",\"value\":\"" + queryId + "\"}],"
                + "\"response_url\":\"http://127.0.0.1:" + serverPort + "/respond\"}";
        return "payload=" + URLEncoder.encode(json, StandardCharsets.UTF_8);
    }

    private String commandBody(String text) {
        return "api_app_id=" + APP_ID
                + "&user_id=U-SLACK-1"
                + "&text=" + URLEncoder.encode(text, StandardCharsets.UTF_8)
                + "&response_url=" + URLEncoder.encode("http://127.0.0.1:" + serverPort + "/respond",
                StandardCharsets.UTF_8);
    }

    private static String nowTs() {
        return Long.toString(Instant.now().getEpochSecond());
    }

    private static String sign(String ts, String body) {
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return "v0=" + HexFormat.of().formatHex(
                    mac.doFinal(("v0:" + ts + ":" + body).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private UserEntity saveUser(String email, UserRoleType role) {
        var user = new UserEntity();
        user.setId(UUID.randomUUID());
        user.setEmail(email);
        user.setDisplayName(role.name());
        user.setPasswordHash("hashed");
        user.setRole(role);
        user.setAuthProvider(AuthProviderType.LOCAL);
        user.setActive(true);
        user.setOrganization(org);
        return userRepository.save(user);
    }

    private String generateToken(UserEntity entity) {
        var view = new com.bablsoft.accessflow.core.api.UserView(
                entity.getId(), entity.getEmail(), entity.getDisplayName(), entity.getRole(),
                entity.getOrganization().getId(), entity.isActive(), entity.getAuthProvider(),
                entity.getPasswordHash(), entity.getLastLoginAt(), entity.getPreferredLanguage(),
                entity.isTotpEnabled(), entity.getCreatedAt());
        return jwtService.generateAccessToken(view);
    }
}
