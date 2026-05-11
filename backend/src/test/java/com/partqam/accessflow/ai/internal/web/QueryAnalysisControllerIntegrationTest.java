package com.partqam.accessflow.ai.internal.web;

import com.partqam.accessflow.TestcontainersConfig;
import com.partqam.accessflow.ai.api.AiAnalysisException;
import com.partqam.accessflow.ai.api.AiAnalysisParseException;
import com.partqam.accessflow.ai.api.AiAnalysisResult;
import com.partqam.accessflow.ai.api.AiAnalyzerService;
import com.partqam.accessflow.ai.api.AiIssue;
import com.partqam.accessflow.core.api.AiProviderType;
import com.partqam.accessflow.core.api.AuthProviderType;
import com.partqam.accessflow.core.api.DatasourceNotFoundException;
import com.partqam.accessflow.core.api.EditionType;
import com.partqam.accessflow.core.api.RiskLevel;
import com.partqam.accessflow.core.api.UserRoleType;
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
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.web.context.WebApplicationContext;

import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateCrtKey;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

@SpringBootTest
@ImportTestcontainers(TestcontainersConfig.class)
class QueryAnalysisControllerIntegrationTest {

    @Autowired WebApplicationContext context;
    @Autowired UserRepository userRepository;
    @Autowired OrganizationRepository organizationRepository;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired JwtService jwtService;
    @MockitoBean AiAnalyzerService aiAnalyzerService;

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
                null, null, false, java.time.Instant.now()));
    }

    @AfterEach
    void cleanup() {
        userRepository.deleteAll();
        organizationRepository.deleteAll();
    }

    @Test
    void analyzeReturnsResultJson() {
        var dsId = UUID.randomUUID();
        var result = new AiAnalysisResult(85, RiskLevel.HIGH, "DELETE without WHERE",
                List.of(new AiIssue(RiskLevel.HIGH, "DELETE_WITHOUT_WHERE",
                        "Deletes all rows", "Add a WHERE clause")),
                false, null, AiProviderType.ANTHROPIC, "claude-sonnet-4-20250514", 100, 50);
        when(aiAnalyzerService.analyzePreview(eq(dsId), eq("DELETE FROM users"),
                eq(analyst.getId()), any(UUID.class), anyBoolean())).thenReturn(result);

        var response = mvc.post().uri("/api/v1/queries/analyze")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + analystToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"datasource_id\":\"%s\",\"sql\":\"DELETE FROM users\"}".formatted(dsId))
                .exchange();

        assertThat(response).hasStatus(200);
        assertThat(response).bodyJson().extractingPath("$.risk_score").asNumber().isEqualTo(85);
        assertThat(response).bodyJson().extractingPath("$.risk_level").asString().isEqualTo("HIGH");
        assertThat(response).bodyJson().extractingPath("$.summary").asString()
                .isEqualTo("DELETE without WHERE");
        assertThat(response).bodyJson().extractingPath("$.issues[0].category").asString()
                .isEqualTo("DELETE_WITHOUT_WHERE");
        assertThat(response).bodyJson().extractingPath("$.ai_provider").asString().isEqualTo("ANTHROPIC");
        assertThat(response).bodyJson().extractingPath("$.prompt_tokens").asNumber().isEqualTo(100);
        assertThat(response).bodyJson().extractingPath("$.completion_tokens").asNumber().isEqualTo(50);
    }

    @Test
    void analyzeWithoutTokenReturns401() {
        var response = mvc.post().uri("/api/v1/queries/analyze")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"datasource_id\":\"%s\",\"sql\":\"SELECT 1\"}".formatted(UUID.randomUUID()))
                .exchange();

        assertThat(response).hasStatus(401);
    }

    @Test
    void analyzeWithInvalidBodyReturns400() {
        var response = mvc.post().uri("/api/v1/queries/analyze")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + analystToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"sql\":\"\"}")
                .exchange();

        assertThat(response).hasStatus(400);
        assertThat(response).bodyJson().extractingPath("$.error").asString().isEqualTo("VALIDATION_ERROR");
    }

    @Test
    void analyzeReturns404WhenDatasourceNotAccessible() {
        var dsId = UUID.randomUUID();
        when(aiAnalyzerService.analyzePreview(eq(dsId), any(), any(), any(), anyBoolean()))
                .thenThrow(new DatasourceNotFoundException(dsId));

        var response = mvc.post().uri("/api/v1/queries/analyze")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + analystToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"datasource_id\":\"%s\",\"sql\":\"SELECT 1\"}".formatted(dsId))
                .exchange();

        assertThat(response).hasStatus(404);
    }

    @Test
    void analyzeReturns503WhenProviderFails() {
        var dsId = UUID.randomUUID();
        when(aiAnalyzerService.analyzePreview(eq(dsId), any(), any(), any(), anyBoolean()))
                .thenThrow(new AiAnalysisException("provider unreachable"));

        var response = mvc.post().uri("/api/v1/queries/analyze")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + analystToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"datasource_id\":\"%s\",\"sql\":\"SELECT 1\"}".formatted(dsId))
                .exchange();

        assertThat(response).hasStatus(503);
        assertThat(response).bodyJson().extractingPath("$.error").asString()
                .isEqualTo("AI_PROVIDER_UNAVAILABLE");
    }

    @Test
    void analyzeReturns422WhenAiResponseInvalid() {
        var dsId = UUID.randomUUID();
        when(aiAnalyzerService.analyzePreview(eq(dsId), any(), any(), any(), anyBoolean()))
                .thenThrow(new AiAnalysisParseException("missing risk_score"));

        var response = mvc.post().uri("/api/v1/queries/analyze")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + analystToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"datasource_id\":\"%s\",\"sql\":\"SELECT 1\"}".formatted(dsId))
                .exchange();

        assertThat(response).hasStatus(422);
        assertThat(response).bodyJson().extractingPath("$.error").asString().isEqualTo("AI_RESPONSE_INVALID");
    }
}
