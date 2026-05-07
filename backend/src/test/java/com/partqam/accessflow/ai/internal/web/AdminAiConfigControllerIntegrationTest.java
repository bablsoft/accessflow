package com.partqam.accessflow.ai.internal.web;

import com.partqam.accessflow.TestcontainersConfig;
import com.partqam.accessflow.ai.api.AiAnalysisException;
import com.partqam.accessflow.ai.api.AiAnalysisResult;
import com.partqam.accessflow.ai.api.AiAnalyzerStrategy;
import com.partqam.accessflow.ai.internal.persistence.repo.AiConfigRepository;
import com.partqam.accessflow.core.api.AiProviderType;
import com.partqam.accessflow.core.api.AuthProviderType;
import com.partqam.accessflow.core.api.CredentialEncryptionService;
import com.partqam.accessflow.core.api.DbType;
import com.partqam.accessflow.core.api.EditionType;
import com.partqam.accessflow.core.api.RiskLevel;
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
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.web.context.WebApplicationContext;

import java.util.List;

import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateCrtKey;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

@SpringBootTest
@ImportTestcontainers(TestcontainersConfig.class)
class AdminAiConfigControllerIntegrationTest {

    @Autowired WebApplicationContext context;
    @Autowired UserRepository userRepository;
    @Autowired OrganizationRepository organizationRepository;
    @Autowired AiConfigRepository repository;
    @Autowired JwtService jwtService;
    @Autowired CredentialEncryptionService encryptionService;
    @MockitoBean AiAnalyzerStrategy aiAnalyzerStrategy;

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
        repository.deleteAll();
        userRepository.deleteAll();
        organizationRepository.deleteAll();

        org = new OrganizationEntity();
        org.setId(UUID.randomUUID());
        org.setName("Primary");
        org.setSlug("primary-" + UUID.randomUUID());
        org.setEdition(EditionType.COMMUNITY);
        organizationRepository.save(org);

        admin = saveUser("admin@example.com", UserRoleType.ADMIN);
        analyst = saveUser("analyst@example.com", UserRoleType.ANALYST);
        adminToken = generateToken(admin);
        analystToken = generateToken(analyst);
    }

    @AfterEach
    void cleanup() {
        repository.deleteAll();
    }

    @Test
    void getReturnsDefaultConfigBeforeAnyUpdate() throws Exception {
        var result = mvc.get().uri("/api/v1/admin/ai-config")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .exchange();

        assertThat(result).hasStatus(200);
        assertThat(result).bodyJson().extractingPath("$.provider").asString().isEqualTo("ANTHROPIC");
        assertThat(result).bodyJson().extractingPath("$.enable_ai_default").asBoolean().isTrue();
        // api_key is absent (null) on default config — Jackson drops null fields.
        assertThat(result.getResponse().getContentAsString()).doesNotContain("\"api_key\"");
    }

    @Test
    void putPersistsConfigAndMasksApiKeyOnReadback() {
        var body = "{\"provider\":\"OPENAI\",\"model\":\"gpt-4o-mini\",\"api_key\":\"sk-test\","
                + "\"timeout_ms\":15000,\"max_prompt_tokens\":4000,\"max_completion_tokens\":1500,"
                + "\"enable_ai_default\":true,\"auto_approve_low\":true,"
                + "\"block_critical\":true,\"include_schema\":false}";

        var put = mvc.put().uri("/api/v1/admin/ai-config")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .exchange();
        assertThat(put).hasStatus(200);
        assertThat(put).bodyJson().extractingPath("$.provider").asString().isEqualTo("OPENAI");
        assertThat(put).bodyJson().extractingPath("$.api_key").asString().isEqualTo("********");

        var get = mvc.get().uri("/api/v1/admin/ai-config")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .exchange();
        assertThat(get).bodyJson().extractingPath("$.model").asString().isEqualTo("gpt-4o-mini");
        assertThat(get).bodyJson().extractingPath("$.timeout_ms").asNumber().isEqualTo(15000);
        assertThat(get).bodyJson().extractingPath("$.api_key").asString().isEqualTo("********");
        assertThat(get).bodyJson().extractingPath("$.include_schema").asBoolean().isFalse();

        var stored = repository.findByOrganizationId(org.getId()).orElseThrow();
        assertThat(stored.getApiKeyEncrypted()).isNotNull();
        assertThat(encryptionService.decrypt(stored.getApiKeyEncrypted())).isEqualTo("sk-test");
    }

    @Test
    void putWithMaskedApiKeyPreservesExistingCipher() {
        // First write a real key.
        var firstBody = "{\"api_key\":\"sk-original\",\"model\":\"claude-sonnet-4-20250514\"}";
        var first = mvc.put().uri("/api/v1/admin/ai-config")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(firstBody)
                .exchange();
        assertThat(first).hasStatus(200);
        var originalCipher = repository.findByOrganizationId(org.getId()).orElseThrow().getApiKeyEncrypted();

        // Update without changing the key.
        var update = "{\"api_key\":\"********\",\"model\":\"claude-sonnet-4-20250514\"}";
        var second = mvc.put().uri("/api/v1/admin/ai-config")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(update)
                .exchange();
        assertThat(second).hasStatus(200);

        var afterCipher = repository.findByOrganizationId(org.getId()).orElseThrow().getApiKeyEncrypted();
        assertThat(afterCipher).isEqualTo(originalCipher);
    }

    @Test
    void rejectsTimeoutBelowRange() {
        var body = "{\"timeout_ms\":10}";
        var result = mvc.put().uri("/api/v1/admin/ai-config")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .exchange();
        assertThat(result).hasStatus(400);
    }

    @Test
    void analystForbidden() {
        var get = mvc.get().uri("/api/v1/admin/ai-config")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + analystToken)
                .exchange();
        assertThat(get).hasStatus(403);
    }

    @Test
    void testEndpointReturnsOkWhenAnalyzerSucceeds() {
        var fakeResult = new AiAnalysisResult(
                10,
                RiskLevel.LOW,
                "ok",
                List.of(),
                false,
                null,
                AiProviderType.ANTHROPIC,
                "claude-sonnet-4-20250514",
                12,
                34);
        when(aiAnalyzerStrategy.analyze(any(), any(), any())).thenReturn(fakeResult);

        var result = mvc.post().uri("/api/v1/admin/ai-config/test")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .exchange();

        assertThat(result).hasStatus(200);
        assertThat(result).bodyJson().extractingPath("$.status").asString().isEqualTo("OK");
        assertThat(result).bodyJson().extractingPath("$.detail").asString().contains("LOW");
        verify(aiAnalyzerStrategy).analyze(eq("SELECT 1"), eq(DbType.POSTGRESQL), eq(null));
    }

    @Test
    void testEndpointReturnsErrorWhenAnalyzerThrows() {
        when(aiAnalyzerStrategy.analyze(any(), any(), any()))
                .thenThrow(new AiAnalysisException("provider unreachable"));

        var result = mvc.post().uri("/api/v1/admin/ai-config/test")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .exchange();

        assertThat(result).hasStatus(200);
        assertThat(result).bodyJson().extractingPath("$.status").asString().isEqualTo("ERROR");
        assertThat(result).bodyJson().extractingPath("$.detail").asString()
                .isEqualTo("provider unreachable");
    }

    @Test
    void testEndpointForbiddenForNonAdmin() {
        var result = mvc.post().uri("/api/v1/admin/ai-config/test")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + analystToken)
                .exchange();

        assertThat(result).hasStatus(403);
    }

    @Test
    void putRejectsModelOver100Chars() {
        var body = "{\"model\":\"" + "x".repeat(101) + "\"}";
        var result = mvc.put().uri("/api/v1/admin/ai-config")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .exchange();
        assertThat(result).hasStatus(400);
    }

    private UserEntity saveUser(String email, UserRoleType role) {
        var user = new UserEntity();
        user.setId(UUID.randomUUID());
        user.setEmail(email);
        user.setDisplayName(role.name());
        user.setPasswordHash("hashed");
        user.setRole(role);
        user.setAuthProvider(AuthProviderType.LOCAL);
        user.setActive(true);
        user.setOrganization(org);
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
