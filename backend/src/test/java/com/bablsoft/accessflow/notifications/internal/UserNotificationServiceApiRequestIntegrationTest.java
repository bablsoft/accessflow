package com.bablsoft.accessflow.notifications.internal;

import com.bablsoft.accessflow.TestcontainersConfig;
import com.bablsoft.accessflow.apigov.internal.persistence.entity.ApiRequestEntity;
import com.bablsoft.accessflow.apigov.internal.persistence.repo.ApiRequestRepository;
import com.bablsoft.accessflow.core.api.AuthProviderType;
import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.core.internal.persistence.entity.OrganizationEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.UserEntity;
import com.bablsoft.accessflow.core.internal.persistence.repo.OrganizationRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.UserRepository;
import com.bablsoft.accessflow.notifications.api.NotificationEventType;
import com.bablsoft.accessflow.notifications.internal.persistence.repo.UserNotificationRepository;
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
import java.util.Base64;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reproduces AF-529: recording an in-app notification for an API request (AF-500) against a real
 * database. Before the fix the API-request id was persisted into {@code user_notifications
 * .query_request_id}, whose FK to {@code query_requests} has no matching row — so this exercise threw
 * a {@code DataIntegrityViolationException}. The existing unit coverage mocked the repository and
 * never hit the constraint.
 */
@SpringBootTest
@ImportTestcontainers(TestcontainersConfig.class)
class UserNotificationServiceApiRequestIntegrationTest {

    @Autowired UserNotificationService service;
    @Autowired UserNotificationRepository notificationRepository;
    @Autowired OrganizationRepository organizationRepository;
    @Autowired UserRepository userRepository;
    @Autowired ApiRequestRepository apiRequestRepository;

    private OrganizationEntity organization;
    private UserEntity recipient;

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
        notificationRepository.deleteAll();
        apiRequestRepository.deleteAll();
        userRepository.deleteAll();
        organizationRepository.deleteAll();

        organization = new OrganizationEntity();
        organization.setId(UUID.randomUUID());
        organization.setName("Primary");
        organization.setSlug("primary-" + UUID.randomUUID());
        organizationRepository.save(organization);

        var user = new UserEntity();
        user.setId(UUID.randomUUID());
        user.setEmail("reviewer@example.com");
        user.setDisplayName("Reviewer");
        user.setPasswordHash("hashed");
        user.setRole(UserRoleType.REVIEWER);
        user.setAuthProvider(AuthProviderType.LOCAL);
        user.setActive(true);
        user.setOrganization(organization);
        recipient = userRepository.save(user);
    }

    @Test
    void recordsApiRequestNotificationWithoutViolatingQueryRequestFk() {
        var apiRequest = new ApiRequestEntity();
        apiRequest.setId(UUID.randomUUID());
        apiRequest.setConnectorId(UUID.randomUUID());
        apiRequest.setOrganizationId(organization.getId());
        apiRequest.setSubmittedBy(UUID.randomUUID());
        apiRequest.setVerb("GET");
        apiRequest.setRequestPath("/v1/things");
        apiRequest.setStatus(QueryStatus.PENDING_REVIEW);
        apiRequestRepository.save(apiRequest);

        service.recordForUsers(NotificationEventType.API_REQUEST_SUBMITTED,
                Set.of(recipient.getId()), organization.getId(),
                /* queryRequestId */ null, apiRequest.getId(), "{\"api_id\":\"x\"}");

        var stored = notificationRepository
                .findByUserIdOrderByCreatedAtDesc(recipient.getId(), PageRequest.of(0, 10))
                .getContent();
        assertThat(stored).singleElement().satisfies(n -> {
            assertThat(n.getEventType()).isEqualTo(NotificationEventType.API_REQUEST_SUBMITTED);
            assertThat(n.getQueryRequestId()).isNull();
            assertThat(n.getApiRequestId()).isEqualTo(apiRequest.getId());
        });
    }

    @Test
    void recordsQueryRequestNotificationWithNullApiRequestId() {
        // The query path stays intact: api_request_id null, query_request_id null here (no seeded
        // query row needed since query_request_id is nullable) — the CHECK allows both-null.
        service.recordForUsers(NotificationEventType.QUERY_APPROVED,
                Set.of(recipient.getId()), organization.getId(),
                /* queryRequestId */ null, /* apiRequestId */ null, "{}");

        var stored = notificationRepository
                .findByUserIdOrderByCreatedAtDesc(recipient.getId(), PageRequest.of(0, 10))
                .getContent();
        assertThat(stored).singleElement().satisfies(n -> {
            assertThat(n.getQueryRequestId()).isNull();
            assertThat(n.getApiRequestId()).isNull();
        });
    }
}
