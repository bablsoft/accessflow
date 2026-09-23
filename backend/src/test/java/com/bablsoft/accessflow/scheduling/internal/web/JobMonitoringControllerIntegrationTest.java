package com.bablsoft.accessflow.scheduling.internal.web;

import com.bablsoft.accessflow.TestcontainersConfig;
import com.bablsoft.accessflow.core.api.AuthProviderType;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.core.api.UserView;
import com.bablsoft.accessflow.core.internal.persistence.entity.OrganizationEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.UserEntity;
import com.bablsoft.accessflow.core.internal.persistence.repo.OrganizationRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.UserRepository;
import com.bablsoft.accessflow.scheduling.api.JobExecutionStatus;
import com.bablsoft.accessflow.scheduling.internal.persistence.entity.JobExecutionEntity;
import com.bablsoft.accessflow.scheduling.internal.persistence.repo.JobExecutionRepository;
import com.bablsoft.accessflow.security.internal.jwt.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.web.context.WebApplicationContext;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

@SpringBootTest
@ImportTestcontainers(TestcontainersConfig.class)
class JobMonitoringControllerIntegrationTest {

    @Autowired WebApplicationContext context;
    @Autowired UserRepository userRepository;
    @Autowired OrganizationRepository organizationRepository;
    @Autowired JobExecutionRepository jobExecutionRepository;
    @Autowired JwtService jwtService;

    private MockMvcTester mvc;
    private String platformToken;
    private String orgAdminToken;

    @BeforeEach
    void setUp() {
        mvc = MockMvcTester.from(context, builder -> builder.apply(springSecurity()).build());
        jobExecutionRepository.deleteAll();
        var suffix = UUID.randomUUID().toString().substring(0, 8);
        var org = new OrganizationEntity();
        org.setId(UUID.randomUUID());
        org.setName("Jobs " + suffix);
        org.setSlug("jobs-" + suffix);
        organizationRepository.save(org);
        platformToken = token(saveUser(org, "platform-" + suffix + "@example.com", true));
        orgAdminToken = token(saveUser(org, "admin-" + suffix + "@example.com", false));
    }

    @Test
    void platformAdminSeesTheRegistryWithTheSchedulerDisabledFlag() {
        var result = mvc.get().uri("/api/v1/platform/jobs")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + platformToken).exchange();

        assertThat(result).hasStatus(200);
        // The suite runs with accessflow.scheduling.enabled=false.
        assertThat(result).bodyJson().extractingPath("$.scheduling_enabled").asBoolean().isFalse();
        // No job list assertion: the shared context may have instantiated (and so scheduled) lazy
        // job beans for other test classes, which the registry correctly reports.
        assertThat(result).bodyJson().extractingPath("$.recording_enabled").asBoolean().isTrue();
        assertThat(result).bodyJson().extractingPath("$.summary_window").asString().isEqualTo("PT24H");
    }

    @Test
    void orgAdminWhoIsNotAPlatformAdminIsForbidden() {
        assertThat(mvc.get().uri("/api/v1/platform/jobs")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + orgAdminToken).exchange()).hasStatus(403);
        assertThat(mvc.get().uri("/api/v1/platform/jobs/QueryTimeoutJob/executions")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + orgAdminToken).exchange()).hasStatus(403);
    }

    @Test
    void anonymousIsUnauthorized() {
        assertThat(mvc.get().uri("/api/v1/platform/jobs").exchange()).hasStatus(401);
    }

    @Test
    void unknownJobIs404() {
        var result = mvc.get().uri("/api/v1/platform/jobs/NoSuchJob/executions")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + platformToken).exchange();

        assertThat(result).hasStatus(404);
        assertThat(result).bodyJson().extractingPath("$.error").asString().isEqualTo("JOB_NOT_FOUND");
    }

    @Test
    void historyIsPagedAndFilteredByStatus() {
        save("AuditSinkDrainJob", JobExecutionStatus.SUCCESS, Instant.parse("2026-09-01T00:00:00Z"));
        save("AuditSinkDrainJob", JobExecutionStatus.FAILED, Instant.parse("2026-09-02T00:00:00Z"));
        save("AuditSinkDrainJob", JobExecutionStatus.FAILED, Instant.parse("2026-09-03T00:00:00Z"));

        var result = mvc.get().uri("/api/v1/platform/jobs/AuditSinkDrainJob/executions?status=FAILED&size=1")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + platformToken).exchange();

        assertThat(result).hasStatus(200);
        assertThat(result).bodyJson().extractingPath("$.total_elements").asNumber().isEqualTo(2);
        assertThat(result).bodyJson().extractingPath("$.content[0].started_at").asString()
                .startsWith("2026-09-03");
        assertThat(result).bodyJson().extractingPath("$.content[0].status").asString().isEqualTo("FAILED");
    }

    private void save(String job, JobExecutionStatus status, Instant startedAt) {
        var row = new JobExecutionEntity();
        row.setId(UUID.randomUUID());
        row.setJobName(job);
        row.setStatus(status);
        row.setStartedAt(startedAt);
        row.setFinishedAt(startedAt.plusSeconds(1));
        row.setDurationMs(1000L);
        jobExecutionRepository.save(row);
    }

    private UserEntity saveUser(OrganizationEntity org, String email, boolean platformAdmin) {
        var user = new UserEntity();
        user.setId(UUID.randomUUID());
        user.setEmail(email);
        user.setDisplayName(email);
        user.setPasswordHash("unused");
        user.setRole(UserRoleType.ADMIN);
        user.setAuthProvider(AuthProviderType.LOCAL);
        user.setActive(true);
        user.setOrganization(org);
        user.setPlatformAdmin(platformAdmin);
        return userRepository.save(user);
    }

    private String token(UserEntity entity) {
        var view = new UserView(entity.getId(), entity.getEmail(), entity.getDisplayName(),
                entity.getRole(), null, entity.roleName(), entity.getOrganization().getId(),
                entity.isActive(), entity.getAuthProvider(), entity.getPasswordHash(),
                entity.getLastLoginAt(), entity.getPreferredLanguage(), entity.isTotpEnabled(),
                entity.isPlatformAdmin(), entity.getCreatedAt());
        return jwtService.generateAccessToken(view);
    }
}
