package com.partqam.accessflow.notifications.internal.persistence.repo;

import com.partqam.accessflow.TestcontainersConfig;
import com.partqam.accessflow.core.api.AuthProviderType;
import com.partqam.accessflow.core.api.EditionType;
import com.partqam.accessflow.core.api.UserRoleType;
import com.partqam.accessflow.core.internal.persistence.entity.OrganizationEntity;
import com.partqam.accessflow.core.internal.persistence.entity.UserEntity;
import com.partqam.accessflow.core.internal.persistence.repo.OrganizationRepository;
import com.partqam.accessflow.core.internal.persistence.repo.UserRepository;
import com.partqam.accessflow.notifications.api.NotificationEventType;
import com.partqam.accessflow.notifications.internal.persistence.entity.UserNotificationEntity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateCrtKey;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ImportTestcontainers(TestcontainersConfig.class)
class UserNotificationRepositoryIntegrationTest {

    @Autowired UserNotificationRepository repository;
    @Autowired OrganizationRepository organizationRepository;
    @Autowired UserRepository userRepository;

    private OrganizationEntity organization;
    private UserEntity userA;
    private UserEntity userB;

    @DynamicPropertySource
    static void env(DynamicPropertyRegistry registry) throws Exception {
        var kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        var kp = kpg.generateKeyPair();
        var pk = (RSAPrivateCrtKey) kp.getPrivate();
        var pem = "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(pk.getEncoded())
                + "\n-----END PRIVATE KEY-----";
        registry.add("accessflow.jwt.private-key", () -> pem);
        registry.add("accessflow.encryption-key", () ->
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef");
        registry.add("accessflow.notifications.public-base-url", () -> "https://app.example.test");
    }

    @BeforeEach
    void setUp() {
        repository.deleteAll();
        userRepository.deleteAll();
        organizationRepository.deleteAll();

        organization = new OrganizationEntity();
        organization.setId(UUID.randomUUID());
        organization.setName("Primary");
        organization.setSlug("primary-" + UUID.randomUUID());
        organization.setEdition(EditionType.COMMUNITY);
        organizationRepository.save(organization);

        userA = saveUser("a@example.com");
        userB = saveUser("b@example.com");
    }

    @AfterEach
    void cleanup() {
        repository.deleteAll();
    }

    @Test
    void findByUserIdReturnsNewestFirst() {
        var older = save(userA, Instant.parse("2026-05-01T10:00:00Z"), false);
        var newer = save(userA, Instant.parse("2026-05-02T10:00:00Z"), false);

        var page = repository.findByUserIdOrderByCreatedAtDesc(userA.getId(),
                PageRequest.of(0, 10));

        assertThat(page.getContent())
                .extracting(UserNotificationEntity::getId)
                .containsExactly(newer.getId(), older.getId());
    }

    @Test
    void findByUserIdScopesToCaller() {
        save(userA, Instant.now(), false);
        save(userB, Instant.now(), false);

        var page = repository.findByUserIdOrderByCreatedAtDesc(userA.getId(),
                PageRequest.of(0, 10));

        assertThat(page.getTotalElements()).isEqualTo(1);
    }

    @Test
    void countByUserIdAndReadFalseExcludesRead() {
        save(userA, Instant.now(), false);
        save(userA, Instant.now(), false);
        save(userA, Instant.now(), true);

        assertThat(repository.countByUserIdAndReadFalse(userA.getId())).isEqualTo(2L);
    }

    @Test
    void findByIdAndUserIdReturnsEmptyForOtherUser() {
        var n = save(userA, Instant.now(), false);

        assertThat(repository.findByIdAndUserId(n.getId(), userB.getId())).isEmpty();
        assertThat(repository.findByIdAndUserId(n.getId(), userA.getId())).isPresent();
    }

    @Test
    void markAllReadForUserFlipsAllUnread() {
        var unread1 = save(userA, Instant.now(), false);
        var unread2 = save(userA, Instant.now(), false);
        var alreadyRead = save(userA, Instant.now(), true);

        var now = Instant.parse("2026-05-08T12:00:00Z");
        var updated = repository.markAllReadForUser(userA.getId(), now);

        assertThat(updated).isEqualTo(2);
        assertThat(repository.findById(unread1.getId()).orElseThrow().isRead()).isTrue();
        assertThat(repository.findById(unread2.getId()).orElseThrow().isRead()).isTrue();
        assertThat(repository.findById(unread2.getId()).orElseThrow().getReadAt()).isEqualTo(now);
        // Already-read entries are not touched.
        assertThat(repository.findById(alreadyRead.getId()).orElseThrow().getReadAt())
                .isNotEqualTo(now);
    }

    private UserNotificationEntity save(UserEntity owner, Instant createdAt, boolean read) {
        var n = new UserNotificationEntity();
        n.setId(UUID.randomUUID());
        n.setUserId(owner.getId());
        n.setOrganizationId(organization.getId());
        n.setEventType(NotificationEventType.QUERY_APPROVED);
        n.setQueryRequestId(null);
        n.setPayloadJson("{}");
        n.setRead(read);
        n.setCreatedAt(createdAt);
        if (read) {
            n.setReadAt(createdAt);
        }
        return repository.save(n);
    }

    private UserEntity saveUser(String email) {
        var user = new UserEntity();
        user.setId(UUID.randomUUID());
        user.setEmail(email);
        user.setDisplayName(email);
        user.setPasswordHash("hashed");
        user.setRole(UserRoleType.ANALYST);
        user.setAuthProvider(AuthProviderType.LOCAL);
        user.setActive(true);
        user.setOrganization(organization);
        return userRepository.save(user);
    }
}
