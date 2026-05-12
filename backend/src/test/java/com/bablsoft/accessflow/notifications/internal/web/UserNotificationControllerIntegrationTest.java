package com.bablsoft.accessflow.notifications.internal.web;

import com.bablsoft.accessflow.TestcontainersConfig;
import com.bablsoft.accessflow.core.api.AuthProviderType;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.core.internal.persistence.entity.OrganizationEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.UserEntity;
import com.bablsoft.accessflow.core.internal.persistence.repo.OrganizationRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.UserRepository;
import com.bablsoft.accessflow.notifications.api.NotificationEventType;
import com.bablsoft.accessflow.notifications.internal.persistence.entity.UserNotificationEntity;
import com.bablsoft.accessflow.notifications.internal.persistence.repo.UserNotificationRepository;
import com.bablsoft.accessflow.security.internal.jwt.JwtService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.web.context.WebApplicationContext;

import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateCrtKey;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

@SpringBootTest
@ImportTestcontainers(TestcontainersConfig.class)
class UserNotificationControllerIntegrationTest {

    @Autowired WebApplicationContext context;
    @Autowired UserRepository userRepository;
    @Autowired OrganizationRepository organizationRepository;
    @Autowired UserNotificationRepository notificationRepository;
    @Autowired JwtService jwtService;

    private MockMvcTester mvc;
    private OrganizationEntity org;
    private UserEntity userA;
    private UserEntity userB;
    private String tokenA;
    private String tokenB;

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

    @BeforeEach
    void setUp() {
        mvc = MockMvcTester.from(context, builder -> builder.apply(springSecurity()).build());
        notificationRepository.deleteAll();
        userRepository.deleteAll();
        organizationRepository.deleteAll();

        org = new OrganizationEntity();
        org.setId(UUID.randomUUID());
        org.setName("Primary");
        org.setSlug("primary-" + UUID.randomUUID());
        organizationRepository.save(org);

        userA = saveUser("a@example.com", UserRoleType.ANALYST);
        userB = saveUser("b@example.com", UserRoleType.ANALYST);
        tokenA = generateToken(userA);
        tokenB = generateToken(userB);
    }

    @AfterEach
    void cleanup() {
        notificationRepository.deleteAll();
    }

    @Test
    void listReturnsOnlyCallersNotifications() {
        seed(userA, false);
        seed(userA, true);
        seed(userB, false);

        var result = mvc.get().uri("/api/v1/notifications")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenA)
                .exchange();

        assertThat(result).hasStatus(200);
        assertThat(result).bodyJson().extractingPath("$.total_elements").asNumber()
                .isEqualTo(2);
    }

    @Test
    void unauthenticatedReturns401() {
        var result = mvc.get().uri("/api/v1/notifications").exchange();
        assertThat(result).hasStatus(401);
    }

    @Test
    void unreadCountReturnsCallersUnreadOnly() {
        seed(userA, false);
        seed(userA, false);
        seed(userA, true);

        var result = mvc.get().uri("/api/v1/notifications/unread-count")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenA)
                .exchange();

        assertThat(result).hasStatus(200);
        assertThat(result).bodyJson().extractingPath("$.count").asNumber().isEqualTo(2);
    }

    @Test
    void markReadFlipsAndReturns204() {
        var n = seed(userA, false);

        var result = mvc.post().uri("/api/v1/notifications/" + n.getId() + "/read")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenA)
                .exchange();

        assertThat(result).hasStatus(204);
        var refreshed = notificationRepository.findById(n.getId()).orElseThrow();
        assertThat(refreshed.isRead()).isTrue();
        assertThat(refreshed.getReadAt()).isNotNull();
    }

    @Test
    void markReadOnSomeoneElsesNotificationReturns404() {
        var n = seed(userB, false);

        var result = mvc.post().uri("/api/v1/notifications/" + n.getId() + "/read")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenA)
                .exchange();

        assertThat(result).hasStatus(404);
        assertThat(result).bodyJson().extractingPath("$.error").asString()
                .isEqualTo("USER_NOTIFICATION_NOT_FOUND");
    }

    @Test
    void markAllReadFlipsAllUnread() {
        seed(userA, false);
        seed(userA, false);
        seed(userA, true);

        var result = mvc.post().uri("/api/v1/notifications/read-all")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenA)
                .exchange();

        assertThat(result).hasStatus(204);
        assertThat(notificationRepository.countByUserIdAndReadFalse(userA.getId())).isZero();
    }

    @Test
    void deleteRemovesOwnedNotification() {
        var n = seed(userA, false);

        var result = mvc.delete().uri("/api/v1/notifications/" + n.getId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenA)
                .exchange();

        assertThat(result).hasStatus(204);
        assertThat(notificationRepository.findById(n.getId())).isEmpty();
    }

    @Test
    void deleteSomeoneElsesReturns404() {
        var n = seed(userB, false);

        var result = mvc.delete().uri("/api/v1/notifications/" + n.getId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenA)
                .exchange();

        assertThat(result).hasStatus(404);
        assertThat(notificationRepository.findById(n.getId())).isPresent();
    }

    private UserNotificationEntity seed(UserEntity user, boolean read) {
        var n = new UserNotificationEntity();
        n.setId(UUID.randomUUID());
        n.setUserId(user.getId());
        n.setOrganizationId(org.getId());
        n.setEventType(NotificationEventType.QUERY_APPROVED);
        n.setQueryRequestId(null);
        n.setPayloadJson("{\"datasource\":\"prod\"}");
        n.setRead(read);
        n.setCreatedAt(Instant.now());
        if (read) {
            n.setReadAt(Instant.now());
        }
        return notificationRepository.save(n);
    }

    private UserEntity saveUser(String email, UserRoleType role) {
        var user = new UserEntity();
        user.setId(UUID.randomUUID());
        user.setEmail(email);
        user.setDisplayName(email);
        user.setPasswordHash("hashed");
        user.setRole(role);
        user.setAuthProvider(AuthProviderType.LOCAL);
        user.setActive(true);
        user.setOrganization(org);
        return userRepository.save(user);
    }

    private String generateToken(UserEntity entity) {
        var view = new com.bablsoft.accessflow.core.api.UserView(
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
                entity.isTotpEnabled(),
                entity.getCreatedAt());
        return jwtService.generateAccessToken(view);
    }
}
