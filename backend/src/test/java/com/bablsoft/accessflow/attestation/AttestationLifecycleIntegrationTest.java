package com.bablsoft.accessflow.attestation;

import com.bablsoft.accessflow.TestcontainersConfig;
import com.bablsoft.accessflow.attestation.api.AttestationCampaignAdminService;
import com.bablsoft.accessflow.attestation.api.AttestationCampaignScope;
import com.bablsoft.accessflow.attestation.api.AttestationCampaignStatus;
import com.bablsoft.accessflow.attestation.api.AttestationEvidenceExportService;
import com.bablsoft.accessflow.attestation.api.AttestationItemDecision;
import com.bablsoft.accessflow.attestation.api.AttestationItemView;
import com.bablsoft.accessflow.attestation.api.AttestationLifecycleService;
import com.bablsoft.accessflow.attestation.api.AttestationPendingDefault;
import com.bablsoft.accessflow.attestation.api.AttestationReviewService;
import com.bablsoft.accessflow.attestation.api.AttestationReviewService.ReviewerContext;
import com.bablsoft.accessflow.attestation.api.CreateAttestationCampaignCommand;
import com.bablsoft.accessflow.core.api.AuthProviderType;
import com.bablsoft.accessflow.core.api.CreatePermissionCommand;
import com.bablsoft.accessflow.core.api.CredentialEncryptionService;
import com.bablsoft.accessflow.core.api.DatasourceAdminService;
import com.bablsoft.accessflow.core.api.DbType;
import com.bablsoft.accessflow.core.api.PageRequest;
import com.bablsoft.accessflow.core.api.SslMode;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.core.internal.persistence.entity.DatasourceEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.OrganizationEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.UserEntity;
import com.bablsoft.accessflow.core.internal.persistence.repo.DatasourceRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.DatasourceUserPermissionRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.OrganizationRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateCrtKey;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ImportTestcontainers(TestcontainersConfig.class)
class AttestationLifecycleIntegrationTest {

    @Autowired OrganizationRepository organizationRepository;
    @Autowired UserRepository userRepository;
    @Autowired DatasourceRepository datasourceRepository;
    @Autowired DatasourceUserPermissionRepository permissionRepository;
    @Autowired CredentialEncryptionService encryptionService;
    @Autowired DatasourceAdminService datasourceAdminService;
    @Autowired AttestationCampaignAdminService adminService;
    @Autowired AttestationLifecycleService lifecycleService;
    @Autowired AttestationReviewService reviewService;
    @Autowired AttestationEvidenceExportService evidenceExportService;
    @Autowired JdbcTemplate jdbcTemplate;

    private OrganizationEntity organization;
    private UserEntity admin;
    private UserEntity subjectA;
    private UserEntity subjectB;
    private DatasourceEntity datasource;

    @DynamicPropertySource
    static void securityProperties(DynamicPropertyRegistry registry) throws Exception {
        var kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        var privateKey = (RSAPrivateCrtKey) kpg.generateKeyPair().getPrivate();
        var pem = "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(privateKey.getEncoded())
                + "\n-----END PRIVATE KEY-----";
        registry.add("accessflow.jwt.private-key", () -> pem);
        registry.add("accessflow.encryption-key", () ->
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef");
    }

    @BeforeEach
    void setUp() {
        cleanup();
        organization = new OrganizationEntity();
        organization.setId(UUID.randomUUID());
        organization.setName("Primary");
        organization.setSlug("primary-" + UUID.randomUUID());
        organizationRepository.save(organization);

        admin = user("admin", UserRoleType.ADMIN);
        subjectA = user("subjectA", UserRoleType.ANALYST);
        subjectB = user("subjectB", UserRoleType.ANALYST);

        datasource = new DatasourceEntity();
        datasource.setId(UUID.randomUUID());
        datasource.setOrganization(organization);
        datasource.setName("DS-" + UUID.randomUUID());
        datasource.setDbType(DbType.POSTGRESQL);
        datasource.setHost("nope.invalid");
        datasource.setPort(65000);
        datasource.setDatabaseName("db");
        datasource.setUsername("u");
        datasource.setPasswordEncrypted(encryptionService.encrypt("p"));
        datasource.setSslMode(SslMode.DISABLE);
        datasource.setConnectionPoolSize(5);
        datasource.setMaxRowsPerQuery(1000);
        datasource.setRequireReviewReads(false);
        datasource.setRequireReviewWrites(false);
        datasource.setAiAnalysisEnabled(false);
        datasource.setActive(true);
        datasourceRepository.save(datasource);

        grant(subjectA);
        grant(subjectB);
    }

    @AfterEach
    void cleanup() {
        jdbcTemplate.update("DELETE FROM attestation_item");
        jdbcTemplate.update("DELETE FROM attestation_campaign");
        jdbcTemplate.update("DELETE FROM user_notifications");
        jdbcTemplate.update("DELETE FROM audit_log");
        permissionRepository.deleteAll();
        datasourceRepository.deleteAll();
        userRepository.deleteAll();
        organizationRepository.deleteAll();
    }

    private UserEntity user(String name, UserRoleType role) {
        var u = new UserEntity();
        u.setId(UUID.randomUUID());
        u.setEmail(name + "-" + UUID.randomUUID() + "@example.com");
        u.setDisplayName(name);
        u.setPasswordHash("hash");
        u.setRole(role);
        u.setAuthProvider(AuthProviderType.LOCAL);
        u.setActive(true);
        u.setOrganization(organization);
        return userRepository.save(u);
    }

    private void grant(UserEntity subject) {
        datasourceAdminService.grantPermission(datasource.getId(), organization.getId(),
                admin.getId(), new CreatePermissionCommand(subject.getId(), true, false, false,
                        false, null, List.of("public"), null, null, null));
    }

    @Test
    void openSnapshotsGrantsReviewerCertifiesAndRevokesThenEvidenceExports() {
        var now = Instant.now();
        var campaign = adminService.create(new CreateAttestationCampaignCommand(
                organization.getId(), admin.getId(), "Quarterly review", "Q3",
                AttestationCampaignScope.DATASOURCE, datasource.getId(),
                AttestationPendingDefault.KEEP, now, now.plus(7, ChronoUnit.DAYS)));

        // Open → two items snapshotted, one per granted subject
        var opened = adminService.openNow(campaign.id(), organization.getId());
        assertThat(opened.status()).isEqualTo(AttestationCampaignStatus.OPEN);
        assertThat(opened.totalItems()).isEqualTo(2);

        var items = adminService.listItems(campaign.id(), organization.getId(),
                PageRequest.of(0, 50)).content();
        assertThat(items).hasSize(2);
        var itemA = items.stream().filter(i -> i.subjectUserId().equals(subjectA.getId()))
                .findFirst().orElseThrow();
        var itemB = items.stream().filter(i -> i.subjectUserId().equals(subjectB.getId()))
                .findFirst().orElseThrow();

        var reviewer = new ReviewerContext(admin.getId(), organization.getId(), UserRoleType.ADMIN);
        reviewService.certify(itemA.id(), reviewer, "still needed");
        reviewService.revoke(itemB.id(), reviewer, "no longer needed");

        // Revoked grant is gone; certified grant remains
        assertThat(permissionRepository.findByUser_IdAndDatasource_Id(subjectB.getId(),
                datasource.getId())).isEmpty();
        assertThat(permissionRepository.findByUser_IdAndDatasource_Id(subjectA.getId(),
                datasource.getId())).isPresent();

        // Close the campaign (no PENDING items remain)
        assertThat(lifecycleService.closeCampaign(campaign.id())).isTrue();
        var closed = adminService.get(campaign.id(), organization.getId());
        assertThat(closed.status()).isEqualTo(AttestationCampaignStatus.CLOSED);
        assertThat(closed.certifiedItems()).isEqualTo(1);
        assertThat(closed.revokedItems()).isEqualTo(1);

        // Evidence export contains both subjects and their decisions
        var export = evidenceExportService.export(campaign.id(), organization.getId());
        var csv = new String(export.content(), StandardCharsets.UTF_8);
        assertThat(export.rowCount()).isEqualTo(2);
        assertThat(csv).contains(subjectA.getEmail()).contains(subjectB.getEmail())
                .contains(AttestationItemDecision.CERTIFIED.name())
                .contains(AttestationItemDecision.REVOKED.name());

        // System audit for OPEN + CLOSE was written
        Integer openedAudits = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM audit_log WHERE action = 'ATTESTATION_CAMPAIGN_OPENED'",
                Integer.class);
        Integer closedAudits = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM audit_log WHERE action = 'ATTESTATION_CAMPAIGN_CLOSED'",
                Integer.class);
        assertThat(openedAudits).isEqualTo(1);
        assertThat(closedAudits).isEqualTo(1);
    }

    @Test
    void worklistListsPendingItemsForEligibleAdminReviewer() {
        var now = Instant.now();
        var campaign = adminService.create(new CreateAttestationCampaignCommand(
                organization.getId(), admin.getId(), "Worklist review", null,
                AttestationCampaignScope.DATASOURCE, datasource.getId(),
                AttestationPendingDefault.KEEP, now, now.plus(7, ChronoUnit.DAYS)));
        adminService.openNow(campaign.id(), organization.getId());

        // The reviewer worklist (cross-join query + eligibility + self-review filter) must surface
        // both PENDING items to the admin reviewer, who is not the subject of either grant and is
        // eligible by admin-fallback (the datasource has no scoped reviewers).
        var reviewer = new ReviewerContext(admin.getId(), organization.getId(), UserRoleType.ADMIN);
        var worklist = reviewService.listPendingForReviewer(reviewer, PageRequest.of(0, 50));

        assertThat(worklist.content())
                .extracting(AttestationItemView::subjectUserEmail)
                .containsExactlyInAnyOrder(subjectA.getEmail(), subjectB.getEmail());
    }

    @Test
    void closeWithRevokeDefaultRevokesUnattestedGrants() {
        var now = Instant.now();
        var campaign = adminService.create(new CreateAttestationCampaignCommand(
                organization.getId(), admin.getId(), "Strict review", null,
                AttestationCampaignScope.DATASOURCE, datasource.getId(),
                AttestationPendingDefault.REVOKE, now, now.plus(7, ChronoUnit.DAYS)));
        adminService.openNow(campaign.id(), organization.getId());

        // Close without any reviewer action → both PENDING items auto-revoked
        assertThat(lifecycleService.closeCampaign(campaign.id())).isTrue();

        assertThat(permissionRepository.findByUser_IdAndDatasource_Id(subjectA.getId(),
                datasource.getId())).isEmpty();
        assertThat(permissionRepository.findByUser_IdAndDatasource_Id(subjectB.getId(),
                datasource.getId())).isEmpty();
        var view = adminService.get(campaign.id(), organization.getId());
        assertThat(view.revokedItems()).isEqualTo(2);
    }
}
