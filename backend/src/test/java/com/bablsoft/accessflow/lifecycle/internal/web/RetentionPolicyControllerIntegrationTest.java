package com.bablsoft.accessflow.lifecycle.internal.web;

import com.bablsoft.accessflow.TestcontainersConfig;
import com.bablsoft.accessflow.audit.api.AuditLogQuery;
import com.bablsoft.accessflow.audit.api.AuditLogService;
import com.bablsoft.accessflow.core.api.AuthProviderType;
import com.bablsoft.accessflow.core.api.PageRequest;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.core.api.UserView;
import com.bablsoft.accessflow.core.internal.persistence.entity.OrganizationEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.UserEntity;
import com.bablsoft.accessflow.core.internal.persistence.repo.OrganizationRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.UserRepository;
import com.bablsoft.accessflow.lifecycle.internal.persistence.repo.RetentionPolicyRepository;
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
class RetentionPolicyControllerIntegrationTest {

    @Autowired WebApplicationContext context;
    @Autowired UserRepository userRepository;
    @Autowired OrganizationRepository organizationRepository;
    @Autowired RetentionPolicyRepository policyRepository;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired JwtService jwtService;
    @Autowired AuditLogService auditLogService;
    @Autowired JdbcTemplate jdbcTemplate;

    private MockMvcTester mvc;
    private OrganizationEntity organization;
    private String adminToken;
    private String analystToken;

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
        adminToken = token(saveUser(org, "admin@example.com", UserRoleType.ADMIN), org);
        analystToken = token(saveUser(org, "analyst@example.com", UserRoleType.ANALYST), org);
    }

    @AfterEach
    void cleanup() {
        jdbcTemplate.update("DELETE FROM lifecycle_runs");
        jdbcTemplate.update("DELETE FROM retention_policies");
        jdbcTemplate.update("DELETE FROM audit_log");
        userRepository.deleteAll();
        organizationRepository.deleteAll();
    }

    private String createBody(String window, String action, String transform) {
        var transformLine = transform == null ? "" : "\"transform_type\":\"" + transform + "\",";
        return """
                {"datasource_id":"%s","name":"Retention","description":"d",
                 "target_table":"orders","target_columns":["email"],
                 "timestamp_column":"created_at","retention_window":"%s",
                 %s"action":"%s","enabled":true}
                """.formatted(UUID.randomUUID(), window, transformLine, action);
    }

    @Test
    void createListGetUpdateDelete() {
        var create = mvc.post().uri("/api/v1/lifecycle/policies")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .header("X-Forwarded-For", "203.0.113.7")
                .header(HttpHeaders.USER_AGENT, "junit-ua/1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(createBody("P30D", "HARD_DELETE", null))
                .exchange();
        assertThat(create).hasStatus(201);
        assertThat(create).bodyJson().extractingPath("$.action").asString().isEqualTo("HARD_DELETE");

        var id = policyRepository.findAll().get(0).getId();

        var audits = auditLogService.query(organization.getId(), AuditLogQuery.empty(),
                PageRequest.of(0, 10)).content();
        assertThat(audits).hasSize(1);
        assertThat(audits.get(0).ipAddress()).isEqualTo("203.0.113.7");

        var list = mvc.get().uri("/api/v1/lifecycle/policies?page=0&size=20&sort=createdAt,desc")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken).exchange();
        assertThat(list).hasStatus(200);
        assertThat(list).bodyJson().extractingPath("$.content.length()").isEqualTo(1);

        var get = mvc.get().uri("/api/v1/lifecycle/policies/{id}", id)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken).exchange();
        assertThat(get).hasStatus(200);
        assertThat(get).bodyJson().extractingPath("$.target_table").asString().isEqualTo("orders");

        var update = mvc.put().uri("/api/v1/lifecycle/policies/{id}", id)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"name":"Retention v2","target_table":"orders","timestamp_column":"created_at",
                         "retention_window":"P7Y","action":"PSEUDONYMIZE","transform_type":"SHA256_SALTED",
                         "enabled":false}
                        """)
                .exchange();
        assertThat(update).hasStatus(200);
        assertThat(update).bodyJson().extractingPath("$.transform_type").asString()
                .isEqualTo("SHA256_SALTED");
        assertThat(update).bodyJson().extractingPath("$.enabled").isEqualTo(false);

        var preview = mvc.post().uri("/api/v1/lifecycle/policies/{id}/preview", id)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken).exchange();
        assertThat(preview).hasStatus(200);
        assertThat(preview).bodyJson().extractingPath("$.action").asString().isEqualTo("PSEUDONYMIZE");

        var delete = mvc.delete().uri("/api/v1/lifecycle/policies/{id}", id)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken).exchange();
        assertThat(delete).hasStatus(204);
        assertThat(policyRepository.findAll()).isEmpty();
    }

    @Test
    void createReturns400OnNoTarget() {
        var res = mvc.post().uri("/api/v1/lifecycle/policies")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"datasource_id":"%s","name":"Retention","timestamp_column":"created_at",
                         "retention_window":"P30D","action":"HARD_DELETE"}
                        """.formatted(UUID.randomUUID()))
                .exchange();
        assertThat(res).hasStatus(400);
        assertThat(res).bodyJson().extractingPath("$.error").asString()
                .isEqualTo("INVALID_RETENTION_POLICY");
        assertThat(res).bodyJson().extractingPath("$.reason").asString().isEqualTo("NO_TARGET");
    }

    @Test
    void createReturns400OnInvalidWindow() {
        var res = mvc.post().uri("/api/v1/lifecycle/policies")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(createBody("30 days", "HARD_DELETE", null))
                .exchange();
        assertThat(res).hasStatus(400);
        assertThat(res).bodyJson().extractingPath("$.reason").asString().isEqualTo("INVALID_WINDOW");
    }

    @Test
    void createReturns400OnBlankName() {
        var res = mvc.post().uri("/api/v1/lifecycle/policies")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"datasource_id":"%s","name":"","timestamp_column":"created_at",
                         "retention_window":"P30D","action":"HARD_DELETE","target_table":"orders"}
                        """.formatted(UUID.randomUUID()))
                .exchange();
        assertThat(res).hasStatus(400);
    }

    @Test
    void getReturns404WhenMissing() {
        var res = mvc.get().uri("/api/v1/lifecycle/policies/{id}", UUID.randomUUID())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken).exchange();
        assertThat(res).hasStatus(404);
        assertThat(res).bodyJson().extractingPath("$.error").asString()
                .isEqualTo("RETENTION_POLICY_NOT_FOUND");
    }

    @Test
    void listReturns403ForNonAdmin() {
        assertThat(mvc.get().uri("/api/v1/lifecycle/policies")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + analystToken).exchange())
                .hasStatus(403);
    }

    @Test
    void listReturns401WithoutToken() {
        assertThat(mvc.get().uri("/api/v1/lifecycle/policies").exchange()).hasStatus(401);
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
        return jwtService.generateAccessToken(new UserView(
                user.getId(), user.getEmail(), user.getDisplayName(), user.getRole(),
                org.getId(), true, AuthProviderType.LOCAL, user.getPasswordHash(),
                null, null, false, Instant.now()));
    }
}
