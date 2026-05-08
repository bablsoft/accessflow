package com.partqam.accessflow.audit.internal.web;

import com.partqam.accessflow.TestcontainersConfig;
import com.partqam.accessflow.audit.api.AuditAction;
import com.partqam.accessflow.audit.api.AuditEntry;
import com.partqam.accessflow.audit.api.AuditLogService;
import com.partqam.accessflow.audit.api.AuditResourceType;
import com.partqam.accessflow.core.api.AuthProviderType;
import com.partqam.accessflow.core.api.EditionType;
import com.partqam.accessflow.core.api.UserRoleType;
import com.partqam.accessflow.core.api.UserView;
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
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.web.context.WebApplicationContext;

import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateCrtKey;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

@SpringBootTest
@ImportTestcontainers(TestcontainersConfig.class)
class AdminAuditLogControllerIntegrationTest {

    @Autowired WebApplicationContext context;
    @Autowired AuditLogService auditLogService;
    @Autowired OrganizationRepository organizationRepository;
    @Autowired UserRepository userRepository;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired JwtService jwtService;

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
        var pem = "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder(64, new byte[]{'\n'})
                .encodeToString(((RSAPrivateCrtKey) kp.getPrivate()).getEncoded())
                + "\n-----END PRIVATE KEY-----";
        registry.add("accessflow.jwt.private-key", () -> pem);
        registry.add("accessflow.encryption-key", () ->
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef");
    }

    @BeforeEach
    void setUp() {
        mvc = MockMvcTester.from(context, builder -> builder.apply(springSecurity()).build());
        cleanup();
        org = new OrganizationEntity();
        org.setId(UUID.randomUUID());
        org.setName("Org");
        org.setSlug("org-" + UUID.randomUUID());
        org.setEdition(EditionType.COMMUNITY);
        organizationRepository.save(org);

        admin = saveUser(org, "admin@example.com", UserRoleType.ADMIN);
        analyst = saveUser(org, "analyst@example.com", UserRoleType.ANALYST);
        adminToken = generateToken(admin);
        analystToken = generateToken(analyst);
    }

    @AfterEach
    void cleanup() {
        jdbcTemplate.update("DELETE FROM audit_log");
        userRepository.deleteAll();
        organizationRepository.deleteAll();
    }

    @Test
    void adminCanReadAuditLog() {
        auditLogService.record(new AuditEntry(
                AuditAction.USER_LOGIN,
                AuditResourceType.USER,
                analyst.getId(),
                org.getId(),
                analyst.getId(),
                Map.of("email", "analyst@example.com"),
                "127.0.0.1",
                "ua/1"));

        var result = mvc.get().uri("/api/v1/admin/audit-log")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .exchange();

        assertThat(result).hasStatus(200);
        assertThat(result).bodyJson().extractingPath("$.total_elements").asNumber().isEqualTo(1);
        assertThat(result).bodyJson().extractingPath("$.content[0].action").asString()
                .isEqualTo("USER_LOGIN");
        assertThat(result).bodyJson().extractingPath("$.content[0].resource_type").asString()
                .isEqualTo("user");
        assertThat(result).bodyJson().extractingPath("$.content[0].ip_address").asString()
                .startsWith("127.0.0.1");
        assertThat(result).bodyJson().extractingPath("$.content[0].actor_email").asString()
                .isEqualTo("analyst@example.com");
        assertThat(result).bodyJson().extractingPath("$.content[0].actor_display_name").asString()
                .isEqualTo("analyst@example.com");
    }

    @Test
    void systemDrivenRowsHaveNullActorEnrichment() throws Exception {
        auditLogService.record(new AuditEntry(
                AuditAction.QUERY_AI_ANALYZED,
                AuditResourceType.QUERY_REQUEST,
                UUID.randomUUID(),
                org.getId(),
                null,
                Map.of("risk", "LOW"),
                null,
                null));

        var result = mvc.get().uri("/api/v1/admin/audit-log")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .exchange();

        assertThat(result).hasStatus(200);
        // Jackson drops nulls, so the absent fields confirm the null actor enrichment.
        var body = result.getResponse().getContentAsString();
        assertThat(body).doesNotContain("\"actor_id\"")
                .doesNotContain("\"actor_email\"")
                .doesNotContain("\"actor_display_name\"")
                .contains("\"action\":\"QUERY_AI_ANALYZED\"");
    }

    @Test
    void analystGets403() {
        var result = mvc.get().uri("/api/v1/admin/audit-log")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + analystToken)
                .exchange();
        assertThat(result).hasStatus(403);
    }

    @Test
    void pageSizeOverLimitRejected() {
        var result = mvc.get().uri("/api/v1/admin/audit-log?size=501")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .exchange();
        assertThat(result).hasStatus(400);
    }

    @Test
    void unknownResourceTypeIs400() {
        var result = mvc.get().uri("/api/v1/admin/audit-log?resourceType=imaginary")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .exchange();
        assertThat(result).hasStatus(400);
    }

    @Test
    void otherOrganizationRowsAreNotVisible() {
        var foreignOrg = new OrganizationEntity();
        foreignOrg.setId(UUID.randomUUID());
        foreignOrg.setName("Other");
        foreignOrg.setSlug("other-" + UUID.randomUUID());
        foreignOrg.setEdition(EditionType.COMMUNITY);
        organizationRepository.save(foreignOrg);
        var foreigner = saveUser(foreignOrg, "stranger@example.com", UserRoleType.ANALYST);

        auditLogService.record(new AuditEntry(
                AuditAction.USER_LOGIN,
                AuditResourceType.USER,
                foreigner.getId(),
                foreignOrg.getId(),
                foreigner.getId(),
                Map.of(),
                null,
                null));

        var result = mvc.get().uri("/api/v1/admin/audit-log")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .exchange();
        assertThat(result).hasStatus(200);
        assertThat(result).bodyJson().extractingPath("$.total_elements").asNumber().isEqualTo(0);
    }

    @Test
    void defaultSortOrdersByCreatedAtDesc() throws InterruptedException {
        auditLogService.record(new AuditEntry(
                AuditAction.USER_LOGIN,
                AuditResourceType.USER,
                analyst.getId(),
                org.getId(),
                analyst.getId(),
                Map.of("seq", "1"),
                null,
                null));
        Thread.sleep(5);
        auditLogService.record(new AuditEntry(
                AuditAction.USER_CREATED,
                AuditResourceType.USER,
                analyst.getId(),
                org.getId(),
                admin.getId(),
                Map.of("seq", "2"),
                null,
                null));

        var result = mvc.get().uri("/api/v1/admin/audit-log")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .exchange();

        assertThat(result).hasStatus(200);
        assertThat(result).bodyJson().extractingPath("$.content[0].action").asString()
                .isEqualTo("USER_CREATED");
        assertThat(result).bodyJson().extractingPath("$.content[1].action").asString()
                .isEqualTo("USER_LOGIN");
    }

    @Test
    void explicitCamelCaseSortIsAccepted() {
        auditLogService.record(new AuditEntry(
                AuditAction.USER_LOGIN,
                AuditResourceType.USER,
                analyst.getId(),
                org.getId(),
                analyst.getId(),
                Map.of(),
                null,
                null));

        var result = mvc.get().uri("/api/v1/admin/audit-log?sort=createdAt,DESC")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .exchange();

        assertThat(result).hasStatus(200);
        assertThat(result).bodyJson().extractingPath("$.total_elements").asNumber().isEqualTo(1);
    }

    @Test
    void snakeCaseSortIs400BadAuditQuery() {
        var result = mvc.get().uri("/api/v1/admin/audit-log?sort=created_at,DESC")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .exchange();

        assertThat(result).hasStatus(400);
        assertThat(result).bodyJson().extractingPath("$.error").asString()
                .isEqualTo("BAD_AUDIT_QUERY");
    }

    @Test
    void unsupportedSortPropertyIs400BadAuditQuery() {
        var result = mvc.get().uri("/api/v1/admin/audit-log?sort=metadata,asc")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .exchange();

        assertThat(result).hasStatus(400);
        assertThat(result).bodyJson().extractingPath("$.error").asString()
                .isEqualTo("BAD_AUDIT_QUERY");
    }

    @Test
    void filterByActionWorks() {
        auditLogService.record(new AuditEntry(
                AuditAction.USER_LOGIN,
                AuditResourceType.USER,
                analyst.getId(),
                org.getId(),
                analyst.getId(),
                Map.of(),
                null,
                null));
        auditLogService.record(new AuditEntry(
                AuditAction.USER_CREATED,
                AuditResourceType.USER,
                analyst.getId(),
                org.getId(),
                admin.getId(),
                Map.of(),
                null,
                null));

        var result = mvc.get().uri("/api/v1/admin/audit-log?action=USER_CREATED")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .exchange();
        assertThat(result).hasStatus(200);
        assertThat(result).bodyJson().extractingPath("$.total_elements").asNumber().isEqualTo(1);
        assertThat(result).bodyJson().extractingPath("$.content[0].action").asString()
                .isEqualTo("USER_CREATED");
    }

    private UserEntity saveUser(OrganizationEntity organization, String email, UserRoleType role) {
        var user = new UserEntity();
        user.setId(UUID.randomUUID());
        user.setEmail(email);
        user.setDisplayName(email);
        user.setPasswordHash("hashed");
        user.setRole(role);
        user.setAuthProvider(AuthProviderType.LOCAL);
        user.setActive(true);
        user.setOrganization(organization);
        return userRepository.save(user);
    }

    private String generateToken(UserEntity entity) {
        var view = new UserView(
                entity.getId(),
                entity.getEmail(),
                entity.getDisplayName(),
                entity.getRole(),
                entity.getOrganization().getId(),
                entity.isActive(),
                entity.getAuthProvider(),
                entity.getPasswordHash(),
                entity.getLastLoginAt(),
                entity.getCreatedAt());
        return jwtService.generateAccessToken(view);
    }
}
