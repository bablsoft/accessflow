package com.bablsoft.accessflow.ai.internal.web;

import com.bablsoft.accessflow.TestcontainersConfig;
import com.bablsoft.accessflow.ai.api.AiAnalysisException;
import com.bablsoft.accessflow.ai.api.AiAnalysisResult;
import com.bablsoft.accessflow.ai.api.AiAnalyzerStrategy;
import com.bablsoft.accessflow.ai.internal.persistence.entity.AiConfigEntity;
import com.bablsoft.accessflow.ai.internal.persistence.repo.AiConfigRepository;
import com.bablsoft.accessflow.audit.api.AuditAction;
import com.bablsoft.accessflow.audit.api.AuditLogQuery;
import com.bablsoft.accessflow.audit.api.AuditLogService;
import com.bablsoft.accessflow.audit.api.AuditResourceType;
import com.bablsoft.accessflow.core.api.AiProviderType;
import com.bablsoft.accessflow.core.api.AuthProviderType;
import com.bablsoft.accessflow.core.api.CredentialEncryptionService;
import com.bablsoft.accessflow.core.api.DbType;
import com.bablsoft.accessflow.core.api.RiskLevel;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.core.api.UserView;
import com.bablsoft.accessflow.core.internal.persistence.entity.DatasourceEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.OrganizationEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.UserEntity;
import com.bablsoft.accessflow.core.internal.persistence.repo.DatasourceRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.OrganizationRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.UserRepository;
import com.bablsoft.accessflow.security.internal.jwt.JwtService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import com.bablsoft.accessflow.core.api.PageRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

@SpringBootTest
@ImportTestcontainers(TestcontainersConfig.class)
class AdminAiConfigsControllerIntegrationTest {

    @Autowired WebApplicationContext context;
    @Autowired UserRepository userRepository;
    @Autowired OrganizationRepository organizationRepository;
    @Autowired DatasourceRepository datasourceRepository;
    @Autowired AiConfigRepository repository;
    @Autowired JwtService jwtService;
    @Autowired CredentialEncryptionService encryptionService;
    @Autowired AuditLogService auditLogService;
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
        datasourceRepository.deleteAll();
        repository.deleteAll();
        userRepository.deleteAll();
        organizationRepository.deleteAll();

        org = new OrganizationEntity();
        org.setId(UUID.randomUUID());
        org.setName("Primary");
        org.setSlug("primary-" + UUID.randomUUID());
        organizationRepository.save(org);

        admin = saveUser("admin@example.com", UserRoleType.ADMIN);
        analyst = saveUser("analyst@example.com", UserRoleType.ANALYST);
        adminToken = generateToken(admin);
        analystToken = generateToken(analyst);
    }

    @AfterEach
    void cleanup() {
        datasourceRepository.deleteAll();
        repository.deleteAll();
    }

    @Test
    void listReturnsConfigsForCallerOrg() {
        seedConfig("First", AiProviderType.ANTHROPIC, "ENC(k)");
        seedConfig("Second", AiProviderType.OPENAI, null);

        var result = mvc.get().uri("/api/v1/admin/ai-configs")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .exchange();

        assertThat(result).hasStatus(200);
        assertThat(result).bodyJson().extractingPath("$[0].name").asString().isEqualTo("First");
        assertThat(result).bodyJson().extractingPath("$[1].name").asString().isEqualTo("Second");
    }

    @Test
    void createReturns201AndAuditRow() {
        var body = """
                {
                  "name": "ClaudeProd",
                  "provider": "ANTHROPIC",
                  "model": "claude-sonnet-4-20250514",
                  "endpoint": "https://api.anthropic.com",
                  "api_key": "sk-test"
                }""";

        var result = mvc.post().uri("/api/v1/admin/ai-configs")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .exchange();

        assertThat(result).hasStatus(201);
        assertThat(result).bodyJson().extractingPath("$.name").asString().isEqualTo("ClaudeProd");
        assertThat(result).bodyJson().extractingPath("$.api_key").asString().isEqualTo("********");

        var audits = auditLogService.query(org.getId(),
                new AuditLogQuery(null, AuditAction.AI_CONFIG_CREATED, AuditResourceType.AI_CONFIG,
                        null, null, null),
                PageRequest.of(0, 10));
        assertThat(audits.content()).hasSize(1);
    }

    @Test
    void createOpenAiCompatibleWithoutEndpointReturns400() {
        var body = """
                {
                  "name": "LocalLLM",
                  "provider": "OPENAI_COMPATIBLE",
                  "model": "qwen2.5"
                }""";

        var result = mvc.post().uri("/api/v1/admin/ai-configs")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .exchange();

        assertThat(result).hasStatus(400);
        assertThat(result).bodyJson().extractingPath("$.error").asString().isEqualTo("AI_CONFIG_ENDPOINT_REQUIRED");
    }

    @Test
    void createKeylessOpenAiCompatibleReturns201() {
        var body = """
                {
                  "name": "LocalLLM",
                  "provider": "OPENAI_COMPATIBLE",
                  "model": "qwen2.5",
                  "endpoint": "http://vllm:8000/v1"
                }""";

        var result = mvc.post().uri("/api/v1/admin/ai-configs")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .exchange();

        assertThat(result).hasStatus(201);
        assertThat(result).bodyJson().extractingPath("$.provider").asString().isEqualTo("OPENAI_COMPATIBLE");
        assertThat(result).bodyJson().extractingPath("$.endpoint").asString().isEqualTo("http://vllm:8000/v1");
        // Keyless config — the api_key field is omitted from the response (null is not serialized).
        assertThat(result).bodyJson().doesNotHavePath("$.api_key");
    }

    @Test
    void createWithFallbackPriorityRoundTripsAndNegativeUpdateClearsIt() {
        var body = """
                {
                  "name": "LocalFallback",
                  "provider": "OLLAMA",
                  "model": "llama3.1:70b",
                  "endpoint": "http://ollama:11434",
                  "fallback_priority": 0
                }""";

        var created = mvc.post().uri("/api/v1/admin/ai-configs")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .exchange();

        assertThat(created).hasStatus(201);
        assertThat(created).bodyJson().extractingPath("$.fallback_priority").asNumber().isEqualTo(0);

        var id = repository.findAllByOrganizationIdOrderByNameAsc(org.getId()).stream()
                .filter(e -> e.getName().equals("LocalFallback"))
                .findFirst().orElseThrow().getId();
        var cleared = mvc.put().uri("/api/v1/admin/ai-configs/" + id)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"fallback_priority\": -1}")
                .exchange();

        assertThat(cleared).hasStatus(200);
        // Cleared priority — the config is no longer a fallback (null is not serialized).
        assertThat(cleared).bodyJson().doesNotHavePath("$.fallback_priority");
    }

    @Test
    void createWithOutOfRangeFallbackPriorityReturns400() {
        var body = """
                {
                  "name": "BadFallback",
                  "provider": "OLLAMA",
                  "model": "llama3.1:70b",
                  "fallback_priority": 101
                }""";

        var result = mvc.post().uri("/api/v1/admin/ai-configs")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .exchange();

        assertThat(result).hasStatus(400);
    }

    @Test
    void createDuplicateNameReturns409() {
        seedConfig("Existing", AiProviderType.ANTHROPIC, null);
        var body = """
                {
                  "name": "existing",
                  "provider": "OPENAI",
                  "model": "gpt-4o"
                }""";

        var result = mvc.post().uri("/api/v1/admin/ai-configs")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .exchange();

        assertThat(result).hasStatus(409);
        assertThat(result).bodyJson().extractingPath("$.error").asString().isEqualTo("AI_CONFIG_NAME_ALREADY_EXISTS");
    }

    @Test
    void updateChangesFieldsAndWritesAudit() {
        var existing = seedConfig("Initial", AiProviderType.ANTHROPIC, "ENC(k)");
        var body = """
                {
                  "name": "Renamed",
                  "model": "claude-sonnet-4-20250514",
                  "api_key": "********"
                }""";

        var result = mvc.put().uri("/api/v1/admin/ai-configs/" + existing.getId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .exchange();

        assertThat(result).hasStatus(200);
        assertThat(result).bodyJson().extractingPath("$.name").asString().isEqualTo("Renamed");

        var audits = auditLogService.query(org.getId(),
                new AuditLogQuery(null, AuditAction.AI_CONFIG_UPDATED, AuditResourceType.AI_CONFIG,
                        null, null, null),
                PageRequest.of(0, 10));
        assertThat(audits.content()).hasSize(1);
    }

    @Test
    void deleteReturns204AndAudit() {
        var existing = seedConfig("Doomed", AiProviderType.ANTHROPIC, null);

        var result = mvc.delete().uri("/api/v1/admin/ai-configs/" + existing.getId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .exchange();

        assertThat(result).hasStatus(204);
        assertThat(repository.findById(existing.getId())).isEmpty();

        var audits = auditLogService.query(org.getId(),
                new AuditLogQuery(null, AuditAction.AI_CONFIG_DELETED, AuditResourceType.AI_CONFIG,
                        null, null, null),
                PageRequest.of(0, 10));
        assertThat(audits.content()).hasSize(1);
    }

    @Test
    void deleteWhenBoundToDatasourceReturns409WithReferences() {
        var existing = seedConfig("Bound", AiProviderType.ANTHROPIC, "ENC(k)");
        seedDatasource("primary-db", existing.getId());

        var result = mvc.delete().uri("/api/v1/admin/ai-configs/" + existing.getId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .exchange();

        assertThat(result).hasStatus(409);
        assertThat(result).bodyJson().extractingPath("$.error").asString().isEqualTo("AI_CONFIG_IN_USE");
        assertThat(result).bodyJson().extractingPath("$.boundDatasources[0].name").asString().isEqualTo("primary-db");
    }

    @Test
    void testProbeUsesStrategyAndReportsOk() throws Exception {
        var existing = seedConfig("Probe", AiProviderType.ANTHROPIC, "ENC(k)");
        when(aiAnalyzerStrategy.analyze(eq("SELECT 1"), eq(DbType.POSTGRESQL), any(), anyString(),
                eq(existing.getId())))
                .thenReturn(new AiAnalysisResult(10, RiskLevel.LOW, "ok",
                        java.util.List.of(), false, null, AiProviderType.ANTHROPIC, "claude-sonnet-4-20250514", 1, 1,
                        java.util.List.of()));

        var result = mvc.post().uri("/api/v1/admin/ai-configs/" + existing.getId() + "/test")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .exchange();

        assertThat(result).hasStatus(200);
        assertThat(result).bodyJson().extractingPath("$.status").asString().isEqualTo("OK");
    }

    @Test
    void testProbeReportsErrorWhenStrategyThrows() {
        var existing = seedConfig("Probe", AiProviderType.ANTHROPIC, "ENC(k)");
        when(aiAnalyzerStrategy.analyze(any(), any(), any(), any(), eq(existing.getId())))
                .thenThrow(new AiAnalysisException("provider down"));

        var result = mvc.post().uri("/api/v1/admin/ai-configs/" + existing.getId() + "/test")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .exchange();

        assertThat(result).hasStatus(200);
        assertThat(result).bodyJson().extractingPath("$.status").asString().isEqualTo("ERROR");
    }

    @Test
    void promptDefaultReturnsBuiltInTemplate() {
        var result = mvc.get().uri("/api/v1/admin/ai-configs/prompt-default")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .exchange();

        assertThat(result).hasStatus(200);
        assertThat(result).bodyJson().extractingPath("$.template").asString().contains("{{sql}}");
    }

    @Test
    void createWithCustomPromptRoundTrips() {
        var body = """
                {
                  "name": "CustomPrompt",
                  "provider": "OPENAI",
                  "model": "gpt-4o",
                  "api_key": "sk-test",
                  "system_prompt_template": "House rules. Analyze {{sql}} on {{db_type}}."
                }""";

        var created = mvc.post().uri("/api/v1/admin/ai-configs")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .exchange();

        assertThat(created).hasStatus(201);
        assertThat(created).bodyJson().extractingPath("$.system_prompt_template").asString()
                .isEqualTo("House rules. Analyze {{sql}} on {{db_type}}.");

        var id = repository.findAllByOrganizationIdOrderByNameAsc(org.getId()).stream()
                .filter(e -> e.getName().equals("CustomPrompt"))
                .findFirst().orElseThrow().getId();
        var fetched = mvc.get().uri("/api/v1/admin/ai-configs/" + id)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .exchange();
        assertThat(fetched).bodyJson().extractingPath("$.system_prompt_template").asString()
                .isEqualTo("House rules. Analyze {{sql}} on {{db_type}}.");
    }

    @Test
    void createWithPromptMissingSqlPlaceholderReturns400() {
        var body = """
                {
                  "name": "BadPrompt",
                  "provider": "OPENAI",
                  "model": "gpt-4o",
                  "api_key": "sk-test",
                  "system_prompt_template": "Analyze the query but forgot the placeholder."
                }""";

        var result = mvc.post().uri("/api/v1/admin/ai-configs")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .exchange();

        assertThat(result).hasStatus(400);
        assertThat(result).bodyJson().extractingPath("$.error").asString().isEqualTo("AI_CONFIG_INVALID_PROMPT");
    }

    @Test
    void updatePromptThenResetToDefaultWithBlank() {
        var existing = seedConfig("Promptable", AiProviderType.OPENAI, "ENC(k)");

        var setBody = """
                {
                  "system_prompt_template": "Custom {{sql}} guidance."
                }""";
        var set = mvc.put().uri("/api/v1/admin/ai-configs/" + existing.getId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(setBody)
                .exchange();
        assertThat(set).hasStatus(200);
        assertThat(set).bodyJson().extractingPath("$.system_prompt_template").asString()
                .isEqualTo("Custom {{sql}} guidance.");

        var resetBody = """
                {
                  "system_prompt_template": ""
                }""";
        var reset = mvc.put().uri("/api/v1/admin/ai-configs/" + existing.getId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(resetBody)
                .exchange();
        assertThat(reset).hasStatus(200);
        // Blank resets to the built-in default — the stored value is null, so the field is omitted.
        assertThat(reset).bodyJson().doesNotHavePath("$.system_prompt_template");
    }

    @Test
    void forbidsNonAdminCallers() {
        var result = mvc.get().uri("/api/v1/admin/ai-configs")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + analystToken)
                .exchange();

        assertThat(result).hasStatus(403);
    }

    @Test
    void rejectsUnauthenticatedCallers() {
        var result = mvc.get().uri("/api/v1/admin/ai-configs").exchange();

        assertThat(result).hasStatus(401);
    }

    private AiConfigEntity seedConfig(String name, AiProviderType provider, String ciphertext) {
        var entity = new AiConfigEntity();
        entity.setId(UUID.randomUUID());
        entity.setOrganizationId(org.getId());
        entity.setName(name);
        entity.setProvider(provider);
        entity.setModel("test-model");
        entity.setEndpoint("https://example.com");
        entity.setApiKeyEncrypted(ciphertext);
        entity.setCreatedAt(Instant.now());
        entity.setUpdatedAt(Instant.now());
        return repository.save(entity);
    }

    private DatasourceEntity seedDatasource(String name, UUID aiConfigId) {
        var entity = new DatasourceEntity();
        entity.setId(UUID.randomUUID());
        entity.setOrganization(org);
        entity.setName(name);
        entity.setDbType(DbType.POSTGRESQL);
        entity.setHost("h");
        entity.setPort(5432);
        entity.setDatabaseName("db");
        entity.setUsername("u");
        entity.setPasswordEncrypted("ENC");
        entity.setAiAnalysisEnabled(true);
        entity.setAiConfigId(aiConfigId);
        entity.setActive(true);
        entity.setCreatedAt(Instant.now());
        return datasourceRepository.save(entity);
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
                entity.getPreferredLanguage(),
                entity.isTotpEnabled(),
                entity.getCreatedAt());
        return jwtService.generateAccessToken(view);
    }
}
