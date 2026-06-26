package com.bablsoft.accessflow.attestation.internal.web;

import com.bablsoft.accessflow.TestcontainersConfig;
import com.bablsoft.accessflow.attestation.api.AttestationCampaignAdminService;
import com.bablsoft.accessflow.attestation.api.AttestationCampaignNotFoundException;
import com.bablsoft.accessflow.attestation.api.AttestationCampaignScope;
import com.bablsoft.accessflow.attestation.api.AttestationCampaignStatus;
import com.bablsoft.accessflow.attestation.api.AttestationCampaignView;
import com.bablsoft.accessflow.attestation.api.AttestationEvidenceExportService;
import com.bablsoft.accessflow.attestation.api.AttestationEvidenceExportService.EvidenceExport;
import com.bablsoft.accessflow.attestation.api.AttestationItemDecision;
import com.bablsoft.accessflow.attestation.api.AttestationItemView;
import com.bablsoft.accessflow.attestation.api.AttestationPendingDefault;
import com.bablsoft.accessflow.attestation.api.IllegalAttestationCampaignTransitionException;
import com.bablsoft.accessflow.audit.api.AuditAction;
import com.bablsoft.accessflow.audit.api.AuditLogQuery;
import com.bablsoft.accessflow.audit.api.AuditLogService;
import com.bablsoft.accessflow.core.api.AuthProviderType;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.web.context.WebApplicationContext;

import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateCrtKey;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

@SpringBootTest
@ImportTestcontainers(TestcontainersConfig.class)
class AttestationCampaignAdminControllerIntegrationTest {

    @Autowired WebApplicationContext context;
    @Autowired UserRepository userRepository;
    @Autowired OrganizationRepository organizationRepository;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired JwtService jwtService;
    @Autowired AuditLogService auditLogService;
    @Autowired JdbcTemplate jdbcTemplate;
    @MockitoBean AttestationCampaignAdminService adminService;
    @MockitoBean AttestationEvidenceExportService evidenceExportService;

    private MockMvcTester mvc;
    private OrganizationEntity organization;
    private UserEntity admin;
    private String adminToken;
    private String analystToken;
    private String auditorToken;

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
        mvc = MockMvcTester.from(context, builder -> builder.apply(springSecurity()).build());
        cleanup();
        var org = new OrganizationEntity();
        org.setId(UUID.randomUUID());
        org.setName("Primary");
        org.setSlug("primary-" + UUID.randomUUID());
        organization = organizationRepository.save(org);

        admin = saveUser(org, "admin@example.com", UserRoleType.ADMIN);
        adminToken = token(admin, org);
        analystToken = token(saveUser(org, "analyst@example.com", UserRoleType.ANALYST), org);
        auditorToken = token(saveUser(org, "auditor@example.com", UserRoleType.AUDITOR), org);
    }

    @AfterEach
    void cleanup() {
        jdbcTemplate.update("DELETE FROM audit_log");
        userRepository.deleteAll();
        organizationRepository.deleteAll();
    }

    @Test
    void listReturns200ForAdmin() {
        when(adminService.list(any(), any(), any())).thenReturn(PageResponse.empty(0, 20));
        var res = mvc.get().uri("/api/v1/admin/attestation-campaigns")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken).exchange();
        assertThat(res).hasStatus(200);
        assertThat(res).bodyJson().extractingPath("$.content").asArray().isEmpty();
    }

    @Test
    void listReturns403ForAnalyst() {
        var res = mvc.get().uri("/api/v1/admin/attestation-campaigns")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + analystToken).exchange();
        assertThat(res).hasStatus(403);
    }

    @Test
    void listReturns401WithoutToken() {
        assertThat(mvc.get().uri("/api/v1/admin/attestation-campaigns").exchange()).hasStatus(401);
    }

    @Test
    void createReturns201() {
        var id = UUID.randomUUID();
        when(adminService.create(any())).thenReturn(campaignView(id, AttestationCampaignStatus.SCHEDULED));
        var res = mvc.post().uri("/api/v1/admin/attestation-campaigns")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"name":"Q3 review","scope":"DATASOURCE",
                         "datasource_id":"%s","pending_default":"KEEP",
                         "scheduled_open_at":"2026-07-01T00:00:00Z","due_at":"2026-07-08T00:00:00Z"}
                        """.formatted(UUID.randomUUID()))
                .exchange();
        assertThat(res).hasStatus(201);
        assertThat(res).bodyJson().extractingPath("$.status").asString().isEqualTo("SCHEDULED");
        assertThat(res).bodyJson().extractingPath("$.pending_default").asString().isEqualTo("KEEP");
    }

    @Test
    void createReturns400OnBlankName() {
        var res = mvc.post().uri("/api/v1/admin/attestation-campaigns")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"name":"","scope":"ORGANIZATION",
                         "scheduled_open_at":"2026-07-01T00:00:00Z","due_at":"2026-07-08T00:00:00Z"}
                        """)
                .exchange();
        assertThat(res).hasStatus(400);
    }

    @Test
    void createReturns400WhenDueBeforeOpen() {
        var res = mvc.post().uri("/api/v1/admin/attestation-campaigns")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"name":"Q3 review","scope":"ORGANIZATION",
                         "scheduled_open_at":"2026-07-08T00:00:00Z","due_at":"2026-07-01T00:00:00Z"}
                        """)
                .exchange();
        assertThat(res).hasStatus(400);
    }

    @Test
    void createReturns400WhenDatasourceScopeMissingDatasource() {
        var res = mvc.post().uri("/api/v1/admin/attestation-campaigns")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"name":"Q3 review","scope":"DATASOURCE",
                         "scheduled_open_at":"2026-07-01T00:00:00Z","due_at":"2026-07-08T00:00:00Z"}
                        """)
                .exchange();
        assertThat(res).hasStatus(400);
    }

    @Test
    void getReturns200() {
        var id = UUID.randomUUID();
        when(adminService.get(eq(id), any())).thenReturn(campaignView(id, AttestationCampaignStatus.OPEN));
        var res = mvc.get().uri("/api/v1/admin/attestation-campaigns/{id}", id)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken).exchange();
        assertThat(res).hasStatus(200);
        assertThat(res).bodyJson().extractingPath("$.id").asString().isEqualTo(id.toString());
        assertThat(res).bodyJson().extractingPath("$.status").asString().isEqualTo("OPEN");
    }

    @Test
    void getReturns404WhenMissing() {
        var id = UUID.randomUUID();
        when(adminService.get(eq(id), any())).thenThrow(new AttestationCampaignNotFoundException(id));
        var res = mvc.get().uri("/api/v1/admin/attestation-campaigns/{id}", id)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken).exchange();
        assertThat(res).hasStatus(404);
        assertThat(res).bodyJson().extractingPath("$.error").asString()
                .isEqualTo("ATTESTATION_CAMPAIGN_NOT_FOUND");
    }

    @Test
    void listItemsReturns200() {
        var id = UUID.randomUUID();
        when(adminService.listItems(eq(id), any(), any()))
                .thenReturn(new PageResponse<>(List.of(itemView()), 0, 20, 1, 1));
        var res = mvc.get().uri("/api/v1/admin/attestation-campaigns/{id}/items", id)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken).exchange();
        assertThat(res).hasStatus(200);
        assertThat(res).bodyJson().extractingPath("$.content[0].subject_user_email").asString()
                .isEqualTo("subject@example.com");
    }

    @Test
    void openReturns200() {
        var id = UUID.randomUUID();
        when(adminService.openNow(eq(id), any()))
                .thenReturn(campaignView(id, AttestationCampaignStatus.OPEN));
        var res = mvc.post().uri("/api/v1/admin/attestation-campaigns/{id}/open", id)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken).exchange();
        assertThat(res).hasStatus(200);
        assertThat(res).bodyJson().extractingPath("$.status").asString().isEqualTo("OPEN");
    }

    @Test
    void cancelReturns204AndWritesAudit() {
        var id = UUID.randomUUID();
        var res = mvc.post().uri("/api/v1/admin/attestation-campaigns/{id}/cancel", id)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .header("X-Forwarded-For", "203.0.113.7")
                .header(HttpHeaders.USER_AGENT, "junit-ua/1")
                .exchange();
        assertThat(res).hasStatus(204);

        var audits = auditLogService.query(organization.getId(), AuditLogQuery.empty(),
                PageRequest.of(0, 10)).content();
        assertThat(audits).hasSize(1);
        assertThat(audits.get(0).action()).isEqualTo(AuditAction.ATTESTATION_CAMPAIGN_CANCELLED);
        assertThat(audits.get(0).resourceId()).isEqualTo(id);
        assertThat(audits.get(0).actorId()).isEqualTo(admin.getId());
        assertThat(audits.get(0).ipAddress()).isEqualTo("203.0.113.7");
    }

    @Test
    void cancelReturns409WhenNotScheduled() {
        var id = UUID.randomUUID();
        org.mockito.Mockito.doThrow(new IllegalAttestationCampaignTransitionException(
                        AttestationCampaignStatus.OPEN, "not scheduled"))
                .when(adminService).cancel(eq(id), any());
        var res = mvc.post().uri("/api/v1/admin/attestation-campaigns/{id}/cancel", id)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken).exchange();
        assertThat(res).hasStatus(409);
        assertThat(res).bodyJson().extractingPath("$.error").asString()
                .isEqualTo("ATTESTATION_CAMPAIGN_INVALID_STATE");
    }

    @Test
    void evidenceExportReturnsCsvForAdminAndWritesAudit() {
        var id = UUID.randomUUID();
        when(evidenceExportService.export(eq(id), any())).thenReturn(new EvidenceExport(
                "item_id,subject_email\r\nx,alice@example.com\r\n".getBytes(), "evidence.csv", 1, false));
        var res = mvc.get().uri("/api/v1/admin/attestation-campaigns/{id}/evidence.csv", id)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .header(HttpHeaders.ACCEPT, "text/csv")
                .exchange();
        assertThat(res).hasStatus(200);
        var response = res.getMvcResult().getResponse();
        assertThat(response.getHeader(HttpHeaders.CONTENT_DISPOSITION)).contains("evidence.csv");
        assertThat(response.getHeader("X-AccessFlow-Export-Truncated")).isEqualTo("false");

        var audits = auditLogService.query(organization.getId(), AuditLogQuery.empty(),
                PageRequest.of(0, 10)).content();
        assertThat(audits).hasSize(1);
        assertThat(audits.get(0).action()).isEqualTo(AuditAction.ATTESTATION_EVIDENCE_EXPORTED);
    }

    @Test
    void evidenceExportAllowedForAuditor() {
        var id = UUID.randomUUID();
        when(evidenceExportService.export(eq(id), any())).thenReturn(new EvidenceExport(
                "h\r\n".getBytes(), "evidence.csv", 0, false));
        var res = mvc.get().uri("/api/v1/admin/attestation-campaigns/{id}/evidence.csv", id)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + auditorToken)
                .header(HttpHeaders.ACCEPT, "text/csv")
                .exchange();
        assertThat(res).hasStatus(200);
    }

    @Test
    void evidenceExportForbiddenForAnalyst() {
        var id = UUID.randomUUID();
        var res = mvc.get().uri("/api/v1/admin/attestation-campaigns/{id}/evidence.csv", id)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + analystToken)
                .header(HttpHeaders.ACCEPT, "text/csv")
                .exchange();
        assertThat(res).hasStatus(403);
    }

    private AttestationCampaignView campaignView(UUID id, AttestationCampaignStatus status) {
        return new AttestationCampaignView(id, organization.getId(), "Q3 review", "desc",
                AttestationCampaignScope.DATASOURCE, UUID.randomUUID(), "Production", status,
                AttestationPendingDefault.KEEP, Instant.parse("2026-07-01T00:00:00Z"),
                Instant.parse("2026-07-08T00:00:00Z"), null, null, 2, 2, 0, 0,
                admin.getId(), Instant.parse("2026-06-20T00:00:00Z"));
    }

    private AttestationItemView itemView() {
        return new AttestationItemView(UUID.randomUUID(), UUID.randomUUID(), organization.getId(),
                UUID.randomUUID(), UUID.randomUUID(), "Production", UUID.randomUUID(),
                "subject@example.com", "Subject", true, false, false, false, null, null,
                AttestationItemDecision.PENDING, null, null, null, null,
                Instant.parse("2026-07-01T00:00:00Z"));
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
