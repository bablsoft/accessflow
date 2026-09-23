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
import com.bablsoft.accessflow.deploygov.internal.persistence.entity.DeploymentRequestEntity;
import com.bablsoft.accessflow.deploygov.internal.persistence.repo.DeploymentRequestRepository;
import com.bablsoft.accessflow.notifications.api.NotificationEventType;
import com.bablsoft.accessflow.notifications.internal.persistence.repo.UserNotificationRepository;
import com.bablsoft.accessflow.schemachange.api.SchemaChangePromotionStatus;
import com.bablsoft.accessflow.schemachange.api.SchemaChangeSetStatus;
import com.bablsoft.accessflow.schemachange.internal.persistence.entity.SchemaChangeSetEntity;
import com.bablsoft.accessflow.schemachange.internal.persistence.entity.SchemaChangeSetPromotionEntity;
import com.bablsoft.accessflow.schemachange.internal.persistence.repo.SchemaChangeSetPromotionRepository;
import com.bablsoft.accessflow.schemachange.internal.persistence.repo.SchemaChangeSetRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
    @Autowired DeploymentRequestRepository deploymentRequestRepository;
    @Autowired SchemaChangeSetRepository changeSetRepository;
    @Autowired SchemaChangeSetPromotionRepository promotionRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    private OrganizationEntity organization;
    private UserEntity recipient;

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
                /* queryRequestId */ null, apiRequest.getId(), /* deploymentRequestId */ null,
                "{\"api_id\":\"x\"}");

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
                /* queryRequestId */ null, /* apiRequestId */ null, /* deploymentRequestId */ null,
                "{}");

        var stored = notificationRepository
                .findByUserIdOrderByCreatedAtDesc(recipient.getId(), PageRequest.of(0, 10))
                .getContent();
        assertThat(stored).singleElement().satisfies(n -> {
            assertThat(n.getQueryRequestId()).isNull();
            assertThat(n.getApiRequestId()).isNull();
            assertThat(n.getDeploymentRequestId()).isNull();
        });
    }

    @Test
    void recordsDeploymentRequestNotificationAgainstItsOwnFkColumn() {
        // AF-695 mirror of the AF-529 exercise: the deployment-request id must land in
        // user_notifications.deployment_request_id (V155), not in either of the other FK columns.
        var deploymentRequest = new DeploymentRequestEntity();
        deploymentRequest.setId(UUID.randomUUID());
        deploymentRequest.setPipelineId(UUID.randomUUID());
        deploymentRequest.setEnvironmentId(UUID.randomUUID());
        deploymentRequest.setOrganizationId(organization.getId());
        deploymentRequest.setSubmittedBy(recipient.getId());
        deploymentRequest.setVersion("2.4.1");
        deploymentRequestRepository.save(deploymentRequest);

        service.recordForUsers(NotificationEventType.QUERY_APPROVED,
                Set.of(recipient.getId()), organization.getId(),
                /* queryRequestId */ null, /* apiRequestId */ null, deploymentRequest.getId(),
                "{\"deployment_id\":\"x\"}");

        var stored = notificationRepository
                .findByUserIdOrderByCreatedAtDesc(recipient.getId(), PageRequest.of(0, 10))
                .getContent();
        assertThat(stored).singleElement().satisfies(n -> {
            assertThat(n.getQueryRequestId()).isNull();
            assertThat(n.getApiRequestId()).isNull();
            assertThat(n.getDeploymentRequestId()).isEqualTo(deploymentRequest.getId());
        });
    }

    @Test
    void recordsSchemaChangePromotionNotificationAgainstItsOwnFkColumn() {
        // #882: the promotion id lands in user_notifications.schema_change_promotion_id (V182).
        var promotion = savedPromotion();

        service.recordForUsers(NotificationEventType.SCHEMA_CHANGE_PROMOTION_APPLIED,
                Set.of(recipient.getId()), organization.getId(),
                /* queryRequestId */ null, /* apiRequestId */ null, /* deploymentRequestId */ null,
                promotion.getId(), "{\"change_set\":\"x\"}");

        var stored = notificationRepository
                .findByUserIdOrderByCreatedAtDesc(recipient.getId(), PageRequest.of(0, 10))
                .getContent();
        assertThat(stored).singleElement().satisfies(n -> {
            assertThat(n.getEventType()).isEqualTo(NotificationEventType.SCHEMA_CHANGE_PROMOTION_APPLIED);
            assertThat(n.getQueryRequestId()).isNull();
            assertThat(n.getApiRequestId()).isNull();
            assertThat(n.getDeploymentRequestId()).isNull();
            assertThat(n.getSchemaChangePromotionId()).isEqualTo(promotion.getId());
        });
    }

    @Test
    void theWidenedCheckStillRejectsTwoTargetsAtOnce() {
        var promotion = savedPromotion();
        var deploymentRequest = new DeploymentRequestEntity();
        deploymentRequest.setId(UUID.randomUUID());
        deploymentRequest.setPipelineId(UUID.randomUUID());
        deploymentRequest.setEnvironmentId(UUID.randomUUID());
        deploymentRequest.setOrganizationId(organization.getId());
        deploymentRequest.setSubmittedBy(recipient.getId());
        deploymentRequest.setVersion("2.4.1");
        deploymentRequestRepository.save(deploymentRequest);
        service.recordForUsers(NotificationEventType.SCHEMA_CHANGE_PROMOTION_APPLIED,
                Set.of(recipient.getId()), organization.getId(), null, null, null, promotion.getId(), "{}");
        var notificationId = notificationRepository
                .findByUserIdOrderByCreatedAtDesc(recipient.getId(), PageRequest.of(0, 10))
                .getContent().getFirst().getId();

        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE user_notifications SET deployment_request_id = ? WHERE id = ?",
                deploymentRequest.getId(), notificationId))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("chk_user_notifications_target");
    }

    private SchemaChangeSetPromotionEntity savedPromotion() {
        var changeSet = new SchemaChangeSetEntity();
        changeSet.setId(UUID.randomUUID());
        changeSet.setOrganizationId(organization.getId());
        changeSet.setPipelineId(UUID.randomUUID());
        changeSet.setName("cs-" + UUID.randomUUID());
        changeSet.setStatus(SchemaChangeSetStatus.ACTIVE);
        changeSetRepository.saveAndFlush(changeSet);
        var promotion = new SchemaChangeSetPromotionEntity();
        promotion.setId(UUID.randomUUID());
        promotion.setOrganizationId(organization.getId());
        promotion.setChangeSet(changeSet);
        promotion.setEnvironmentId(UUID.randomUUID());
        promotion.setDatasourceId(UUID.randomUUID());
        promotion.setStatus(SchemaChangePromotionStatus.APPLIED);
        promotion.setStatementsChecksum("a".repeat(64));
        promotion.setPromotedBy(recipient.getId());
        return promotionRepository.saveAndFlush(promotion);
    }
}
