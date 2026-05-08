package com.partqam.accessflow.notifications.internal.web;

import com.partqam.accessflow.TestcontainersConfig;
import com.partqam.accessflow.core.api.AuthProviderType;
import com.partqam.accessflow.core.api.CredentialEncryptionService;
import com.partqam.accessflow.core.api.EditionType;
import com.partqam.accessflow.core.api.UserRoleType;
import com.partqam.accessflow.core.internal.persistence.entity.OrganizationEntity;
import com.partqam.accessflow.core.internal.persistence.entity.UserEntity;
import com.partqam.accessflow.core.internal.persistence.repo.OrganizationRepository;
import com.partqam.accessflow.core.internal.persistence.repo.UserRepository;
import com.partqam.accessflow.notifications.internal.codec.ChannelConfigCodec;
import com.partqam.accessflow.notifications.internal.persistence.repo.NotificationChannelRepository;
import com.partqam.accessflow.security.internal.jwt.JwtService;
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

import java.net.InetSocketAddress;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateCrtKey;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

@SpringBootTest
@ImportTestcontainers(TestcontainersConfig.class)
class AdminNotificationChannelControllerIntegrationTest {

    @Autowired WebApplicationContext context;
    @Autowired UserRepository userRepository;
    @Autowired OrganizationRepository organizationRepository;
    @Autowired NotificationChannelRepository channelRepository;
    @Autowired JwtService jwtService;
    @Autowired CredentialEncryptionService encryptionService;
    @Autowired ChannelConfigCodec channelConfigCodec;

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
                + Base64.getMimeEncoder(64, new byte[]{'\n'})
                .encodeToString(privateKey.getEncoded())
                + "\n-----END PRIVATE KEY-----";
        registry.add("accessflow.jwt.private-key", () -> pem);
        registry.add("accessflow.encryption-key", () ->
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef");
        registry.add("accessflow.notifications.public-base-url", () -> "https://app.example.test");
    }

    @BeforeAll
    static void startServer() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            var bytes = exchange.getRequestBody().readAllBytes();
            RECEIVED.add(new String(bytes));
            exchange.sendResponseHeaders(204, -1);
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
        channelRepository.deleteAll();
        userRepository.deleteAll();
        organizationRepository.deleteAll();

        org = new OrganizationEntity();
        org.setId(UUID.randomUUID());
        org.setName("Primary");
        org.setSlug("primary-" + UUID.randomUUID());
        org.setEdition(EditionType.COMMUNITY);
        organizationRepository.save(org);

        admin = saveUser("admin@example.com", UserRoleType.ADMIN);
        analyst = saveUser("analyst@example.com", UserRoleType.ANALYST);
        adminToken = generateToken(admin);
        analystToken = generateToken(analyst);
    }

    @AfterEach
    void cleanup() {
        channelRepository.deleteAll();
    }

    @Test
    void createWebhookChannelMasksSecretOnRead() throws Exception {
        var body = "{"
                + "\"name\":\"Eng webhook\","
                + "\"channel_type\":\"WEBHOOK\","
                + "\"config\":{\"url\":\"http://127.0.0.1:" + serverPort + "/hook\","
                + "\"secret\":\"topsecret\",\"timeout_seconds\":5}}";

        var created = mvc.post().uri("/api/v1/admin/notification-channels")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .exchange();
        assertThat(created).hasStatus(201);
        assertThat(created).bodyJson().extractingPath("$.config.secret").asString()
                .isEqualTo("********");

        var listed = mvc.get().uri("/api/v1/admin/notification-channels")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .exchange();
        assertThat(listed).hasStatus(200);
        assertThat(listed.getResponse().getContentAsString()).contains("********")
                .doesNotContain("topsecret");
    }

    @Test
    void updateWithMaskedSecretPreservesCipher() throws Exception {
        var createBody = "{"
                + "\"name\":\"Eng webhook\","
                + "\"channel_type\":\"WEBHOOK\","
                + "\"config\":{\"url\":\"http://127.0.0.1:" + serverPort + "/hook\","
                + "\"secret\":\"originalsecret\"}}";
        var created = mvc.post().uri("/api/v1/admin/notification-channels")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(createBody)
                .exchange();
        assertThat(created).hasStatus(201);
        var id = created.getResponse().getContentAsString()
                .replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");

        var updateBody = "{\"name\":\"Eng webhook v2\","
                + "\"config\":{\"secret\":\"********\"}}";
        var updated = mvc.put().uri("/api/v1/admin/notification-channels/" + id)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(updateBody)
                .exchange();
        assertThat(updated).hasStatus(200);
        assertThat(updated).bodyJson().extractingPath("$.name").asString()
                .isEqualTo("Eng webhook v2");

        var entity = channelRepository.findById(UUID.fromString(id)).orElseThrow();
        // Decode through the codec so we don't fight the JSON format.
        var decoded = channelConfigCodec.decodeWebhook(entity.getConfigJson());
        assertThat(decoded.secretPlain()).isEqualTo("originalsecret");
    }

    @Test
    void postTestForWebhookHitsServer() throws Exception {
        var createBody = "{"
                + "\"name\":\"Eng webhook\","
                + "\"channel_type\":\"WEBHOOK\","
                + "\"config\":{\"url\":\"http://127.0.0.1:" + serverPort + "/hook\","
                + "\"secret\":\"x\"}}";
        var created = mvc.post().uri("/api/v1/admin/notification-channels")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(createBody)
                .exchange();
        assertThat(created).hasStatus(201);
        var id = created.getResponse().getContentAsString()
                .replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");
        RECEIVED.clear();

        var test = mvc.post().uri("/api/v1/admin/notification-channels/" + id + "/test")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .exchange();
        assertThat(test).hasStatus(200);
        assertThat(RECEIVED).anyMatch(b -> b.contains("\"event\":\"TEST\""));
    }

    @Test
    void analystForbidden() {
        var test = mvc.get().uri("/api/v1/admin/notification-channels")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + analystToken)
                .exchange();
        assertThat(test).hasStatus(403);
    }

    @Test
    void rejectsConfigMissingRequiredKeys() {
        var body = "{"
                + "\"name\":\"Bad\","
                + "\"channel_type\":\"EMAIL\","
                + "\"config\":{\"smtp_host\":\"smtp.example.com\"}}";
        var result = mvc.post().uri("/api/v1/admin/notification-channels")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .exchange();
        assertThat(result).hasStatus(422);
        assertThat(result).bodyJson().extractingPath("$.error").asString()
                .isEqualTo("NOTIFICATION_CHANNEL_CONFIG_INVALID");
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
        var view = new com.partqam.accessflow.core.api.UserView(
                entity.getId(),
                entity.getEmail(),
                entity.getDisplayName(),
                entity.getRole(),
                entity.getOrganization().getId(),
                entity.isActive(),
                entity.getAuthProvider(),
                entity.getPasswordHash(),
                entity.getLastLoginAt(),
                entity.getPreferredLanguage(),
                entity.getCreatedAt());
        return jwtService.generateAccessToken(view);
    }
}
