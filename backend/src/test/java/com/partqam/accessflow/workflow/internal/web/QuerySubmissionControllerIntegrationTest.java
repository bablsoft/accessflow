package com.partqam.accessflow.workflow.internal.web;

import com.partqam.accessflow.TestcontainersConfig;
import com.partqam.accessflow.core.api.AuthProviderType;
import com.partqam.accessflow.core.api.DatasourceNotFoundException;
import com.partqam.accessflow.core.api.EditionType;
import com.partqam.accessflow.core.api.QueryStatus;
import com.partqam.accessflow.core.api.UserRoleType;
import com.partqam.accessflow.core.internal.persistence.entity.OrganizationEntity;
import com.partqam.accessflow.core.internal.persistence.entity.UserEntity;
import com.partqam.accessflow.core.internal.persistence.repo.OrganizationRepository;
import com.partqam.accessflow.core.internal.persistence.repo.UserRepository;
import com.partqam.accessflow.proxy.api.InvalidSqlException;
import com.partqam.accessflow.security.internal.jwt.JwtService;
import com.partqam.accessflow.workflow.api.QuerySubmissionService;
import com.partqam.accessflow.workflow.api.QuerySubmissionService.QuerySubmissionResult;
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
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

@SpringBootTest
@ImportTestcontainers(TestcontainersConfig.class)
class QuerySubmissionControllerIntegrationTest {

    @Autowired WebApplicationContext context;
    @Autowired UserRepository userRepository;
    @Autowired OrganizationRepository organizationRepository;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired JwtService jwtService;
    @MockitoBean QuerySubmissionService querySubmissionService;

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
        org.setEdition(EditionType.COMMUNITY);
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

        analystToken = jwtService.generateAccessToken(new com.partqam.accessflow.core.api.UserView(
                analyst.getId(), analyst.getEmail(), analyst.getDisplayName(), analyst.getRole(),
                org.getId(), true, AuthProviderType.LOCAL, analyst.getPasswordHash(),
                null, null, false, Instant.now()));
    }

    @AfterEach
    void cleanup() {
        userRepository.deleteAll();
        organizationRepository.deleteAll();
    }

    @Test
    void submitReturns202WithPendingAi() {
        var queryId = UUID.randomUUID();
        when(querySubmissionService.submit(any()))
                .thenReturn(new QuerySubmissionResult(queryId, QueryStatus.PENDING_AI));

        var response = mvc.post().uri("/api/v1/queries")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + analystToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"datasource_id\":\"%s\",\"sql\":\"SELECT 1\",\"justification\":\"ticket-42\"}"
                        .formatted(UUID.randomUUID()))
                .exchange();

        assertThat(response).hasStatus(202);
        assertThat(response).bodyJson().extractingPath("$.id").asString()
                .isEqualTo(queryId.toString());
        assertThat(response).bodyJson().extractingPath("$.status").asString().isEqualTo("PENDING_AI");
        assertThat(response).bodyJson().extractingPath("$.ai_analysis").isNull();
        assertThat(response).bodyJson().extractingPath("$.review_plan").isNull();
        assertThat(response).bodyJson().extractingPath("$.estimated_review_completion").isNull();
    }

    @Test
    void submitWithoutTokenReturns401() {
        var response = mvc.post().uri("/api/v1/queries")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"datasource_id\":\"%s\",\"sql\":\"SELECT 1\"}".formatted(UUID.randomUUID()))
                .exchange();

        assertThat(response).hasStatus(401);
    }

    @Test
    void submitWithMissingDatasourceIdReturns400() {
        var response = mvc.post().uri("/api/v1/queries")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + analystToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"sql\":\"SELECT 1\"}")
                .exchange();

        assertThat(response).hasStatus(400);
        assertThat(response).bodyJson().extractingPath("$.error").asString()
                .isEqualTo("VALIDATION_ERROR");
    }

    @Test
    void submitWithBlankSqlReturns400() {
        var response = mvc.post().uri("/api/v1/queries")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + analystToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"datasource_id\":\"%s\",\"sql\":\"\"}".formatted(UUID.randomUUID()))
                .exchange();

        assertThat(response).hasStatus(400);
        assertThat(response).bodyJson().extractingPath("$.error").asString()
                .isEqualTo("VALIDATION_ERROR");
    }

    @Test
    void submitReturns404WhenDatasourceNotAccessible() {
        var dsId = UUID.randomUUID();
        when(querySubmissionService.submit(any()))
                .thenThrow(new DatasourceNotFoundException(dsId));

        var response = mvc.post().uri("/api/v1/queries")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + analystToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"datasource_id\":\"%s\",\"sql\":\"SELECT 1\"}".formatted(dsId))
                .exchange();

        assertThat(response).hasStatus(404);
        assertThat(response).bodyJson().extractingPath("$.error").asString()
                .isEqualTo("DATASOURCE_NOT_FOUND");
    }

    @Test
    void submitReturns422WhenSqlInvalid() {
        when(querySubmissionService.submit(any()))
                .thenThrow(new InvalidSqlException("cannot parse"));

        var response = mvc.post().uri("/api/v1/queries")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + analystToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"datasource_id\":\"%s\",\"sql\":\"NOT SQL\"}".formatted(UUID.randomUUID()))
                .exchange();

        assertThat(response).hasStatus(422);
        assertThat(response).bodyJson().extractingPath("$.error").asString().isEqualTo("INVALID_SQL");
    }

    @Test
    void submitReturns403WhenPermissionDenied() {
        when(querySubmissionService.submit(any()))
                .thenThrow(new AccessDeniedException("no permission"));

        var response = mvc.post().uri("/api/v1/queries")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + analystToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"datasource_id\":\"%s\",\"sql\":\"SELECT 1\"}".formatted(UUID.randomUUID()))
                .exchange();

        assertThat(response).hasStatus(403);
        assertThat(response).bodyJson().extractingPath("$.error").asString().isEqualTo("FORBIDDEN");
    }
}
