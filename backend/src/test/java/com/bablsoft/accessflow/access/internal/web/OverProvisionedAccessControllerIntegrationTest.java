package com.bablsoft.accessflow.access.internal.web;

import com.bablsoft.accessflow.TestcontainersConfig;
import com.bablsoft.accessflow.access.api.GrantUsageExportService;
import com.bablsoft.accessflow.access.api.GrantUsageExportService.UsageExport;
import com.bablsoft.accessflow.access.api.GrantUsageRecommendation;
import com.bablsoft.accessflow.access.api.GrantUsageReportQuery;
import com.bablsoft.accessflow.access.api.GrantUsageService;
import com.bablsoft.accessflow.access.api.GrantUsageView;
import com.bablsoft.accessflow.audit.api.AuditAction;
import com.bablsoft.accessflow.audit.api.AuditLogQuery;
import com.bablsoft.accessflow.audit.api.AuditLogService;
import com.bablsoft.accessflow.core.api.AuthProviderType;
import com.bablsoft.accessflow.core.api.GrantResourceKind;
import com.bablsoft.accessflow.core.api.PageRequest;
import com.bablsoft.accessflow.core.api.PageResponse;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.core.api.UserView;
import com.bablsoft.accessflow.core.internal.persistence.entity.OrganizationEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.UserEntity;
import com.bablsoft.accessflow.core.internal.persistence.repo.OrganizationRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.UserRepository;
import com.bablsoft.accessflow.security.internal.jwt.JwtService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.web.context.WebApplicationContext;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

@SpringBootTest
@ImportTestcontainers(TestcontainersConfig.class)
class OverProvisionedAccessControllerIntegrationTest {

    private static final String BASE = "/api/v1/admin/over-provisioned-access";

    @Autowired WebApplicationContext context;
    @Autowired UserRepository userRepository;
    @Autowired OrganizationRepository organizationRepository;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired JwtService jwtService;
    @Autowired AuditLogService auditLogService;
    @Autowired JdbcTemplate jdbcTemplate;
    @MockitoBean GrantUsageService grantUsageService;
    @MockitoBean GrantUsageExportService exportService;

    private MockMvcTester mvc;
    private OrganizationEntity organization;
    private String adminToken;
    private String auditorToken;
    private String analystToken;

    @BeforeEach
    void setUp() {
        mvc = MockMvcTester.from(context, builder -> builder.apply(springSecurity()).build());
        cleanup();
        var org = new OrganizationEntity();
        org.setId(UUID.randomUUID());
        org.setName("Primary");
        org.setSlug("primary-" + UUID.randomUUID());
        organization = organizationRepository.save(org);

        adminToken = token(saveUser(org, "admin@example.com", UserRoleType.ADMIN), org);
        auditorToken = token(saveUser(org, "auditor@example.com", UserRoleType.AUDITOR), org);
        analystToken = token(saveUser(org, "analyst@example.com", UserRoleType.ANALYST), org);
    }

    @AfterEach
    void cleanup() {
        jdbcTemplate.update("DELETE FROM audit_log");
        userRepository.deleteAll();
        organizationRepository.deleteAll();
    }

    private GrantUsageView view() {
        var now = Instant.now();
        return new GrantUsageView(UUID.randomUUID(), organization.getId(),
                GrantResourceKind.DATASOURCE, UUID.randomUUID(), "analytics", UUID.randomUUID(),
                UUID.randomUUID(), "dev@example.com", "Dev", now.minus(Duration.ofDays(200)), null,
                12, List.of("public.orders"), 2, 37, now.minus(Duration.ofDays(90)),
                now.minus(Duration.ofDays(2)), now.minus(Duration.ofDays(90)),
                GrantUsageRecommendation.OVER_SCOPED);
    }

    // ------------------------------------------------------------------ report

    @Test
    void listReturns200ForAdmin() {
        when(grantUsageService.report(any(), any(), any()))
                .thenReturn(new PageResponse<>(List.of(view()), 0, 20, 1, 1));

        var res = mvc.get().uri(BASE)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken).exchange();

        assertThat(res).hasStatus(200);
        assertThat(res).bodyJson().extractingPath("$.content[0].recommendation").asString()
                .isEqualTo("OVER_SCOPED");
        assertThat(res).bodyJson().extractingPath("$.content[0].user_email").asString()
                .isEqualTo("dev@example.com");
        assertThat(res).bodyJson().extractingPath("$.content[0].unused_target_count")
                .asNumber().isEqualTo(10);
    }

    @Test
    void listIsAllowedForAuditor() {
        when(grantUsageService.report(any(), any(), any())).thenReturn(PageResponse.empty(0, 20));

        assertThat(mvc.get().uri(BASE)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + auditorToken).exchange())
                .hasStatus(200);
    }

    @Test
    void listIsForbiddenForAnalystAndAnonymous() {
        assertThat(mvc.get().uri(BASE)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + analystToken).exchange())
                .hasStatus(403);
        assertThat(mvc.get().uri(BASE).exchange()).hasStatus(401);
    }

    @Test
    void listBindsEverySnakeCaseFilterOntoTheQuery() {
        when(grantUsageService.report(any(), any(), any())).thenReturn(PageResponse.empty(0, 20));
        var resourceId = UUID.randomUUID();
        var userId = UUID.randomUUID();

        var res = mvc.get().uri(BASE + "?resource_kind=API_CONNECTOR&recommendation=NEVER_USED"
                        + "&recommendation=STALE&resource_id=" + resourceId + "&user_id=" + userId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken).exchange();

        assertThat(res).hasStatus(200);
        var captor = ArgumentCaptor.forClass(GrantUsageReportQuery.class);
        org.mockito.Mockito.verify(grantUsageService).report(any(), captor.capture(), any());
        var query = captor.getValue();
        assertThat(query.resourceKind()).isEqualTo(GrantResourceKind.API_CONNECTOR);
        assertThat(query.recommendations()).containsExactlyInAnyOrder(
                GrantUsageRecommendation.NEVER_USED, GrantUsageRecommendation.STALE);
        assertThat(query.resourceId()).isEqualTo(resourceId);
        assertThat(query.userId()).isEqualTo(userId);
    }

    /** Nullable figures are facts, not zeros — they must reach the client as JSON null. */
    @Test
    void listSerialisesUnrestrictedAndNeverUsedGrantsAsNulls() {
        var now = Instant.now();
        when(grantUsageService.report(any(), any(), any())).thenReturn(new PageResponse<>(List.of(
                new GrantUsageView(UUID.randomUUID(), organization.getId(),
                        GrantResourceKind.DATASOURCE, UUID.randomUUID(), "analytics",
                        UUID.randomUUID(), UUID.randomUUID(), "dev@example.com", "Dev",
                        now.minus(Duration.ofDays(200)), null, null, List.of(), 0, 0, null, null,
                        now.minus(Duration.ofDays(90)), GrantUsageRecommendation.NEVER_USED)),
                0, 20, 1, 1));

        var res = mvc.get().uri(BASE)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken).exchange();

        assertThat(res).bodyJson().extractingPath("$.content[0].granted_target_count").isNull();
        assertThat(res).bodyJson().extractingPath("$.content[0].unused_target_count").isNull();
        assertThat(res).bodyJson().extractingPath("$.content[0].days_since_last_use").isNull();
    }

    @Test
    void listRejectsAnUnknownRecommendationValue() {
        assertThat(mvc.get().uri(BASE + "?recommendation=NOT_A_VALUE")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken).exchange())
                .hasStatus(400);
    }

    // ------------------------------------------------------------------ export

    @Test
    void exportReturnsCsvWithDispositionTruncationHeaderAndAnAuditRow() {
        when(exportService.export(any(), any())).thenReturn(new UsageExport(
                "summary_id\r\nx\r\n".getBytes(), "over-provisioned-access-20260601T103000Z.csv",
                1, false));

        var res = mvc.get().uri(BASE + "/export.csv")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .header(HttpHeaders.ACCEPT, "text/csv")
                .exchange();

        assertThat(res).hasStatus(200);
        var response = res.getMvcResult().getResponse();
        assertThat(response.getHeader(HttpHeaders.CONTENT_DISPOSITION))
                .contains("over-provisioned-access-20260601T103000Z.csv");
        assertThat(response.getHeader("X-AccessFlow-Export-Truncated")).isEqualTo("false");

        var audits = auditLogService.query(organization.getId(), AuditLogQuery.empty(),
                PageRequest.of(0, 10)).content();
        assertThat(audits).hasSize(1);
        assertThat(audits.get(0).action())
                .isEqualTo(AuditAction.OVER_PROVISIONED_ACCESS_EXPORTED);
    }

    @Test
    void exportFlagsTruncation() {
        when(exportService.export(any(), any())).thenReturn(new UsageExport(
                "h\r\n".getBytes(), "over-provisioned-access.csv", 50_000, true));

        var res = mvc.get().uri(BASE + "/export.csv")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .header(HttpHeaders.ACCEPT, "text/csv")
                .exchange();

        assertThat(res.getMvcResult().getResponse().getHeader("X-AccessFlow-Export-Truncated"))
                .isEqualTo("true");
    }

    @Test
    void exportIsAllowedForAuditorAndForbiddenForAnalyst() {
        when(exportService.export(any(), any()))
                .thenReturn(new UsageExport("h\r\n".getBytes(), "x.csv", 0, false));

        assertThat(mvc.get().uri(BASE + "/export.csv")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + auditorToken)
                .header(HttpHeaders.ACCEPT, "text/csv").exchange()).hasStatus(200);
        assertThat(mvc.get().uri(BASE + "/export.csv")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + analystToken)
                .header(HttpHeaders.ACCEPT, "text/csv").exchange()).hasStatus(403);
    }

    /** The applied filters go into the audit row so a small export is distinguishable from a
     *  filtered one. */
    @Test
    void exportRecordsTheAppliedFiltersInTheAuditMetadata() {
        when(exportService.export(any(), any()))
                .thenReturn(new UsageExport("h\r\n".getBytes(), "x.csv", 0, false));

        mvc.get().uri(BASE + "/export.csv?resource_kind=DATASOURCE&recommendation=NEVER_USED")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .header(HttpHeaders.ACCEPT, "text/csv")
                .exchange();

        var audits = auditLogService.query(organization.getId(), AuditLogQuery.empty(),
                PageRequest.of(0, 10)).content();
        assertThat(audits).hasSize(1);
        assertThat(audits.get(0).metadata())
                .containsEntry("resource_kind", "DATASOURCE")
                .containsEntry("row_count", 0)
                .containsEntry("truncated", false);
        assertThat(audits.get(0).metadata().get("recommendations").toString())
                .contains("NEVER_USED");
    }

    private UserEntity saveUser(OrganizationEntity org, String email, UserRoleType role) {
        var user = new UserEntity();
        user.setId(UUID.randomUUID());
        user.setEmail(email);
        user.setDisplayName(email);
        user.setPasswordHash(passwordEncoder.encode("Password123!"));
        user.setRole(role);
        user.setAuthProvider(AuthProviderType.LOCAL);
        user.setActive(true);
        user.setOrganization(org);
        return userRepository.save(user);
    }

    private String token(UserEntity user, OrganizationEntity org) {
        return jwtService.generateAccessToken(new UserView(user.getId(), user.getEmail(),
                user.getDisplayName(), user.getRole(), org.getId(), true, AuthProviderType.LOCAL,
                user.getPasswordHash(), null, null, false, Instant.now()));
    }
}
