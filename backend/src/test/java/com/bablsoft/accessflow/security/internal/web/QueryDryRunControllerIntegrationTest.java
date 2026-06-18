package com.bablsoft.accessflow.security.internal.web;

import com.bablsoft.accessflow.TestcontainersConfig;
import com.bablsoft.accessflow.core.api.AuthProviderType;
import com.bablsoft.accessflow.core.api.DatasourceNotFoundException;
import com.bablsoft.accessflow.core.api.QueryDryRunResult;
import com.bablsoft.accessflow.core.api.QueryPlanNode;
import com.bablsoft.accessflow.core.api.QueryType;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.core.internal.persistence.entity.OrganizationEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.UserEntity;
import com.bablsoft.accessflow.core.internal.persistence.repo.OrganizationRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.UserRepository;
import com.bablsoft.accessflow.proxy.api.QueryDryRunService;
import com.bablsoft.accessflow.security.internal.jwt.JwtService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.web.context.WebApplicationContext;

import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateCrtKey;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

@SpringBootTest
@ImportTestcontainers(TestcontainersConfig.class)
class QueryDryRunControllerIntegrationTest {

    @Autowired WebApplicationContext context;
    @Autowired UserRepository userRepository;
    @Autowired OrganizationRepository organizationRepository;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired JwtService jwtService;
    @MockitoBean QueryDryRunService queryDryRunService;

    private MockMvcTester mvc;
    private UserEntity analyst;
    private String analystToken;

    @DynamicPropertySource
    static void securityProperties(DynamicPropertyRegistry registry) throws Exception {
        var kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        var kp = kpg.generateKeyPair();
        var privateKey = (RSAPrivateCrtKey) kp.getPrivate();
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
        userRepository.deleteAll();
        organizationRepository.deleteAll();

        var org = new OrganizationEntity();
        org.setId(UUID.randomUUID());
        org.setName("Primary");
        org.setSlug("primary");
        organizationRepository.save(org);

        analyst = new UserEntity();
        analyst.setId(UUID.randomUUID());
        analyst.setEmail("analyst@example.com");
        analyst.setDisplayName("Analyst");
        analyst.setPasswordHash(passwordEncoder.encode("Password123!"));
        analyst.setRole(UserRoleType.ANALYST);
        analyst.setAuthProvider(AuthProviderType.LOCAL);
        analyst.setActive(true);
        analyst.setOrganization(org);
        userRepository.save(analyst);

        analystToken = jwtService.generateAccessToken(new com.bablsoft.accessflow.core.api.UserView(
                analyst.getId(), analyst.getEmail(), analyst.getDisplayName(), analyst.getRole(),
                org.getId(), true, AuthProviderType.LOCAL, analyst.getPasswordHash(),
                null, null, false, java.time.Instant.now()));
    }

    @AfterEach
    void cleanup() {
        userRepository.deleteAll();
        organizationRepository.deleteAll();
    }

    @Test
    void dryRunReturnsPlanJson() {
        var dsId = UUID.randomUUID();
        var plan = new QueryPlanNode("Seq Scan", "users", 1000.0, 25.5, "(age > 21)",
                List.of(new QueryPlanNode("Index Scan", "idx", 5.0, 8.0, null)));
        var result = QueryDryRunResult.of("postgresql", QueryType.SELECT, 1000L, plan, "{...}",
                Set.of(), Duration.ofMillis(12));
        when(queryDryRunService.dryRun(eq(dsId), eq("SELECT * FROM users WHERE age > 21"),
                eq(analyst.getId()), any(UUID.class), anyBoolean())).thenReturn(result);

        var response = mvc.post().uri("/api/v1/queries/dry-run")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + analystToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"datasource_id\":\"%s\",\"sql\":\"SELECT * FROM users WHERE age > 21\"}"
                        .formatted(dsId))
                .exchange();

        assertThat(response).hasStatus(200);
        assertThat(response).bodyJson().extractingPath("$.supported").asBoolean().isTrue();
        assertThat(response).bodyJson().extractingPath("$.query_type").asString().isEqualTo("SELECT");
        assertThat(response).bodyJson().extractingPath("$.estimated_rows").asNumber().isEqualTo(1000);
        assertThat(response).bodyJson().extractingPath("$.plan.operation").asString()
                .isEqualTo("Seq Scan");
        assertThat(response).bodyJson().extractingPath("$.plan.estimated_rows").asNumber()
                .isEqualTo(1000.0);
        assertThat(response).bodyJson().extractingPath("$.plan.children[0].operation").asString()
                .isEqualTo("Index Scan");
        assertThat(response).bodyJson().extractingPath("$.duration_ms").asNumber().isEqualTo(12);
    }

    @Test
    void dryRunUnsupportedReturns200WithReason() {
        var dsId = UUID.randomUUID();
        var result = QueryDryRunResult.unsupported("redis",
                "Dry-run is not supported for the redis engine");
        when(queryDryRunService.dryRun(eq(dsId), any(), any(), any(), anyBoolean()))
                .thenReturn(result);

        var response = mvc.post().uri("/api/v1/queries/dry-run")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + analystToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"datasource_id\":\"%s\",\"sql\":\"GET foo\"}".formatted(dsId))
                .exchange();

        assertThat(response).hasStatus(200);
        assertThat(response).bodyJson().extractingPath("$.supported").asBoolean().isFalse();
        assertThat(response).bodyJson().extractingPath("$.unsupported_reason").asString()
                .contains("redis");
    }

    @Test
    void dryRunWithoutTokenReturns401() {
        var response = mvc.post().uri("/api/v1/queries/dry-run")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"datasource_id\":\"%s\",\"sql\":\"SELECT 1\"}".formatted(UUID.randomUUID()))
                .exchange();

        assertThat(response).hasStatus(401);
    }

    @Test
    void dryRunWithInvalidBodyReturns400() {
        var response = mvc.post().uri("/api/v1/queries/dry-run")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + analystToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"sql\":\"\"}")
                .exchange();

        assertThat(response).hasStatus(400);
        assertThat(response).bodyJson().extractingPath("$.error").asString()
                .isEqualTo("VALIDATION_ERROR");
    }

    @Test
    void dryRunReturns404WhenDatasourceNotAccessible() {
        var dsId = UUID.randomUUID();
        when(queryDryRunService.dryRun(eq(dsId), any(), any(), any(), anyBoolean()))
                .thenThrow(new DatasourceNotFoundException(dsId));

        var response = mvc.post().uri("/api/v1/queries/dry-run")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + analystToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"datasource_id\":\"%s\",\"sql\":\"SELECT 1\"}".formatted(dsId))
                .exchange();

        assertThat(response).hasStatus(404);
    }

    @Test
    void dryRunReturns403WhenForbidden() {
        var dsId = UUID.randomUUID();
        when(queryDryRunService.dryRun(eq(dsId), any(), any(), any(), anyBoolean()))
                .thenThrow(new AccessDeniedException("not allowed"));

        var response = mvc.post().uri("/api/v1/queries/dry-run")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + analystToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"datasource_id\":\"%s\",\"sql\":\"DELETE FROM secrets\"}".formatted(dsId))
                .exchange();

        assertThat(response).hasStatus(403);
    }
}
