package com.bablsoft.accessflow.ai.internal.web;

import com.bablsoft.accessflow.TestcontainersConfig;
import com.bablsoft.accessflow.ai.api.AiAnalysisException;
import com.bablsoft.accessflow.ai.api.AiAnalysisParseException;
import com.bablsoft.accessflow.ai.api.GeneratedSqlResult;
import com.bablsoft.accessflow.ai.api.TextToSqlDisabledException;
import com.bablsoft.accessflow.ai.api.TextToSqlNotConfiguredException;
import com.bablsoft.accessflow.ai.api.TextToSqlService;
import com.bablsoft.accessflow.core.api.AiProviderType;
import com.bablsoft.accessflow.core.api.AuthProviderType;
import com.bablsoft.accessflow.core.api.UserRoleType;
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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.web.context.WebApplicationContext;

import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateCrtKey;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

@SpringBootTest
@ImportTestcontainers(TestcontainersConfig.class)
class TextToSqlControllerIntegrationTest {

    @Autowired WebApplicationContext context;
    @Autowired UserRepository userRepository;
    @Autowired OrganizationRepository organizationRepository;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired JwtService jwtService;
    @MockitoBean TextToSqlService textToSqlService;

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

    private org.springframework.test.web.servlet.assertj.MvcTestResult generate(UUID dsId, String prompt) {
        return mvc.post().uri("/api/v1/queries/generate-sql")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + analystToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"datasource_id\":\"%s\",\"prompt\":\"%s\"}".formatted(dsId, prompt))
                .exchange();
    }

    @Test
    void generateReturnsSqlJson() {
        var dsId = UUID.randomUUID();
        when(textToSqlService.generateSql(eq(dsId), eq("orders for last 5 days"),
                eq(analyst.getId()), any(UUID.class), anyBoolean()))
                .thenReturn(new GeneratedSqlResult("SELECT order_number FROM orders",
                        AiProviderType.ANTHROPIC, "claude-sonnet-4-20250514", 30, 12).withSyntax("sql"));

        var response = generate(dsId, "orders for last 5 days");

        assertThat(response).hasStatus(200);
        assertThat(response).bodyJson().extractingPath("$.sql").asString()
                .isEqualTo("SELECT order_number FROM orders");
        assertThat(response).bodyJson().extractingPath("$.ai_provider").asString().isEqualTo("ANTHROPIC");
        assertThat(response).bodyJson().extractingPath("$.prompt_tokens").asNumber().isEqualTo(30);
        assertThat(response).bodyJson().extractingPath("$.syntax").asString().isEqualTo("sql");
    }

    @Test
    void generateWithoutTokenReturns401() {
        var response = mvc.post().uri("/api/v1/queries/generate-sql")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"datasource_id\":\"%s\",\"prompt\":\"x\"}".formatted(UUID.randomUUID()))
                .exchange();

        assertThat(response).hasStatus(401);
    }

    @Test
    void generateWithBlankPromptReturns400() {
        var response = generate(UUID.randomUUID(), "");

        assertThat(response).hasStatus(400);
        assertThat(response).bodyJson().extractingPath("$.error").asString().isEqualTo("VALIDATION_ERROR");
    }

    @Test
    void generateReturns409WhenDisabled() {
        var dsId = UUID.randomUUID();
        when(textToSqlService.generateSql(eq(dsId), any(), any(), any(), anyBoolean()))
                .thenThrow(new TextToSqlDisabledException());

        var response = generate(dsId, "x");

        assertThat(response).hasStatus(409);
        assertThat(response).bodyJson().extractingPath("$.error").asString().isEqualTo("TEXT_TO_SQL_DISABLED");
    }

    @Test
    void generateReturns400WhenNotConfigured() {
        var dsId = UUID.randomUUID();
        when(textToSqlService.generateSql(eq(dsId), any(), any(), any(), anyBoolean()))
                .thenThrow(new TextToSqlNotConfiguredException());

        var response = generate(dsId, "x");

        assertThat(response).hasStatus(400);
        assertThat(response).bodyJson().extractingPath("$.error").asString()
                .isEqualTo("TEXT_TO_SQL_NOT_CONFIGURED");
    }

    @Test
    void generateReturns422WhenAiResponseInvalid() {
        var dsId = UUID.randomUUID();
        when(textToSqlService.generateSql(eq(dsId), any(), any(), any(), anyBoolean()))
                .thenThrow(new AiAnalysisParseException("no sql field"));

        var response = generate(dsId, "x");

        assertThat(response).hasStatus(422);
        assertThat(response).bodyJson().extractingPath("$.error").asString().isEqualTo("AI_RESPONSE_INVALID");
    }

    @Test
    void generateReturns503WhenProviderFails() {
        var dsId = UUID.randomUUID();
        when(textToSqlService.generateSql(eq(dsId), any(), any(), any(), anyBoolean()))
                .thenThrow(new AiAnalysisException("provider unreachable"));

        var response = generate(dsId, "x");

        assertThat(response).hasStatus(503);
        assertThat(response).bodyJson().extractingPath("$.error").asString()
                .isEqualTo("AI_PROVIDER_UNAVAILABLE");
    }
}
