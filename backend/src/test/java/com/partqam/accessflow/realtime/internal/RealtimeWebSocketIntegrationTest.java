package com.partqam.accessflow.realtime.internal;

import com.partqam.accessflow.TestcontainersConfig;
import com.partqam.accessflow.core.api.AuthProviderType;
import com.partqam.accessflow.core.api.EditionType;
import com.partqam.accessflow.core.api.QueryStatus;
import com.partqam.accessflow.core.api.UserRoleType;
import com.partqam.accessflow.core.api.UserView;
import com.partqam.accessflow.core.events.QueryStatusChangedEvent;
import com.partqam.accessflow.core.internal.persistence.entity.OrganizationEntity;
import com.partqam.accessflow.core.internal.persistence.entity.UserEntity;
import com.partqam.accessflow.core.internal.persistence.repo.OrganizationRepository;
import com.partqam.accessflow.core.internal.persistence.repo.UserRepository;
import com.partqam.accessflow.security.internal.jwt.JwtService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;
import tools.jackson.databind.ObjectMapper;

import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateCrtKey;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ImportTestcontainers(TestcontainersConfig.class)
class RealtimeWebSocketIntegrationTest {

    @LocalServerPort int port;

    @Autowired ApplicationEventPublisher eventPublisher;
    @Autowired JwtService jwtService;
    @Autowired UserRepository userRepository;
    @Autowired OrganizationRepository organizationRepository;
    @Autowired ObjectMapper objectMapper;
    @Autowired PlatformTransactionManager transactionManager;

    private final BlockingQueue<String> received = new ArrayBlockingQueue<>(8);
    private WebSocketSession session;

    @DynamicPropertySource
    static void rsaProperties(DynamicPropertyRegistry registry) throws Exception {
        var kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        var kp = kpg.generateKeyPair();
        var privateKey = (RSAPrivateCrtKey) kp.getPrivate();
        var pem = "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(privateKey.getEncoded())
                + "\n-----END PRIVATE KEY-----";
        registry.add("accessflow.jwt.private-key", () -> pem);
    }

    @AfterEach
    void cleanup() throws Exception {
        if (session != null && session.isOpen()) {
            session.close(CloseStatus.NORMAL);
        }
        userRepository.deleteAll();
        organizationRepository.deleteAll();
    }

    @Test
    void rejectsHandshakeWhenTokenIsMissing() {
        var client = new StandardWebSocketClient();
        var future = client.execute(silentHandler(),
                "ws://localhost:" + port + "/ws");

        // Connection attempt should fail (no token).
        assertThat(future).failsWithin(java.time.Duration.ofSeconds(5));
    }

    @Test
    void deliversStatusChangedEnvelopeToConnectedSubmitter() throws Exception {
        var user = persistUser();
        var token = jwtService.generateAccessToken(toUserView(user));

        session = openSession(token);

        // Wait briefly for the SessionRegistry to register us.
        TimeUnit.MILLISECONDS.sleep(200);

        // ApplicationModuleListener fires AFTER_COMMIT, so the event must be published from
        // inside a real transaction or it will be silently dropped.
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                eventPublisher.publishEvent(new QueryStatusChangedEvent(
                        UUID.randomUUID(), user.getId(),
                        QueryStatus.PENDING_AI, QueryStatus.PENDING_REVIEW)));

        var raw = received.poll(5, TimeUnit.SECONDS);
        assertThat(raw).as("WS message should arrive within 5 s").isNotNull();
        var envelope = objectMapper.readTree(raw);
        assertThat(envelope.get("event").asText()).isEqualTo("query.status_changed");
        assertThat(envelope.get("data").get("old_status").asText()).isEqualTo("PENDING_AI");
        assertThat(envelope.get("data").get("new_status").asText()).isEqualTo("PENDING_REVIEW");
    }

    private WebSocketSession openSession(String token) throws Exception {
        var client = new StandardWebSocketClient();
        var future = client.execute(new TextHandler(),
                "ws://localhost:" + port + "/ws?token=" + token);
        return future.get(5, TimeUnit.SECONDS);
    }

    private UserEntity persistUser() {
        var org = new OrganizationEntity();
        org.setId(UUID.randomUUID());
        org.setName("WS Test Org");
        org.setSlug("ws-test-" + UUID.randomUUID());
        org.setEdition(EditionType.COMMUNITY);
        organizationRepository.save(org);

        var user = new UserEntity();
        user.setId(UUID.randomUUID());
        user.setOrganization(org);
        user.setEmail("ws-test-" + UUID.randomUUID() + "@example.com");
        user.setDisplayName("WS Test User");
        user.setRole(UserRoleType.ANALYST);
        user.setActive(true);
        user.setAuthProvider(AuthProviderType.LOCAL);
        user.setPasswordHash("not-used");
        userRepository.save(user);
        return user;
    }

    private static UserView toUserView(UserEntity user) {
        return new UserView(user.getId(), user.getEmail(), user.getDisplayName(),
                user.getRole(), user.getOrganization().getId(), user.isActive(),
                user.getAuthProvider(), user.getPasswordHash(),
                null, Instant.now());
    }

    private WebSocketHandler silentHandler() {
        return new AbstractWebSocketHandler() {};
    }

    private final class TextHandler extends AbstractWebSocketHandler {
        @Override
        protected void handleTextMessage(WebSocketSession session, TextMessage message) {
            received.offer(message.getPayload());
        }
    }
}
