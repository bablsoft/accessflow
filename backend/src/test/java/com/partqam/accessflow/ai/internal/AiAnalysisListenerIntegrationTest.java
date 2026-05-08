package com.partqam.accessflow.ai.internal;

import com.partqam.accessflow.TestcontainersConfig;
import com.partqam.accessflow.ai.api.AiAnalysisResult;
import com.partqam.accessflow.ai.api.AiAnalyzerStrategy;
import com.partqam.accessflow.ai.api.AiIssue;
import com.partqam.accessflow.core.api.AiProviderType;
import com.partqam.accessflow.core.api.AuthProviderType;
import com.partqam.accessflow.core.api.CredentialEncryptionService;
import com.partqam.accessflow.core.api.DbType;
import com.partqam.accessflow.core.api.EditionType;
import com.partqam.accessflow.core.api.QueryStatus;
import com.partqam.accessflow.core.api.QueryType;
import com.partqam.accessflow.core.api.RiskLevel;
import com.partqam.accessflow.core.api.SslMode;
import com.partqam.accessflow.core.api.UserRoleType;
import com.partqam.accessflow.core.events.QuerySubmittedEvent;
import com.partqam.accessflow.core.internal.persistence.entity.DatasourceEntity;
import com.partqam.accessflow.core.internal.persistence.entity.OrganizationEntity;
import com.partqam.accessflow.core.internal.persistence.entity.QueryRequestEntity;
import com.partqam.accessflow.core.internal.persistence.entity.UserEntity;
import com.partqam.accessflow.core.internal.persistence.repo.AiAnalysisRepository;
import com.partqam.accessflow.core.internal.persistence.repo.DatasourceRepository;
import com.partqam.accessflow.core.internal.persistence.repo.OrganizationRepository;
import com.partqam.accessflow.core.internal.persistence.repo.QueryRequestRepository;
import com.partqam.accessflow.core.internal.persistence.repo.UserRepository;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateCrtKey;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@SpringBootTest
@ImportTestcontainers(TestcontainersConfig.class)
class AiAnalysisListenerIntegrationTest {

    @Autowired ApplicationEventPublisher eventPublisher;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired OrganizationRepository organizationRepository;
    @Autowired UserRepository userRepository;
    @Autowired DatasourceRepository datasourceRepository;
    @Autowired QueryRequestRepository queryRequestRepository;
    @Autowired AiAnalysisRepository aiAnalysisRepository;
    @Autowired CredentialEncryptionService encryptionService;
    @Autowired JdbcTemplate jdbcTemplate;

    @MockitoBean AiAnalyzerStrategy strategy;

    private UUID queryRequestId;

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
        cleanup();
        var org = new OrganizationEntity();
        org.setId(UUID.randomUUID());
        org.setName("Primary");
        org.setSlug("primary");
        org.setEdition(EditionType.COMMUNITY);
        organizationRepository.save(org);

        var user = new UserEntity();
        user.setId(UUID.randomUUID());
        user.setEmail("analyst@example.com");
        user.setDisplayName("Analyst");
        user.setPasswordHash("hash");
        user.setRole(UserRoleType.ANALYST);
        user.setAuthProvider(AuthProviderType.LOCAL);
        user.setActive(true);
        user.setOrganization(org);
        userRepository.save(user);

        var ds = new DatasourceEntity();
        ds.setId(UUID.randomUUID());
        ds.setOrganization(org);
        ds.setName("DS-" + UUID.randomUUID());
        ds.setDbType(DbType.POSTGRESQL);
        ds.setHost("nope.invalid");
        ds.setPort(65000);
        ds.setDatabaseName("db");
        ds.setUsername("u");
        ds.setPasswordEncrypted(encryptionService.encrypt("p"));
        ds.setSslMode(SslMode.DISABLE);
        ds.setConnectionPoolSize(5);
        ds.setMaxRowsPerQuery(1000);
        ds.setRequireReviewReads(false);
        ds.setRequireReviewWrites(true);
        ds.setAiAnalysisEnabled(true);
        ds.setActive(true);
        datasourceRepository.save(ds);

        var query = new QueryRequestEntity();
        query.setId(UUID.randomUUID());
        query.setDatasource(ds);
        query.setSubmittedBy(user);
        query.setSqlText("DELETE FROM users");
        query.setQueryType(QueryType.DELETE);
        query.setStatus(QueryStatus.PENDING_AI);
        queryRequestRepository.save(query);
        queryRequestId = query.getId();
    }

    @AfterEach
    void cleanup() {
        // Break the circular FK between query_requests.ai_analysis_id and ai_analyses.id
        jdbcTemplate.update("UPDATE query_requests SET ai_analysis_id = NULL");
        aiAnalysisRepository.deleteAll();
        queryRequestRepository.deleteAll();
        datasourceRepository.deleteAll();
        userRepository.deleteAll();
        organizationRepository.deleteAll();
    }

    @Test
    void onSubmittedPersistsAnalysisAndLinksQueryRequest() {
        var result = new AiAnalysisResult(85, RiskLevel.HIGH, "DELETE without WHERE",
                List.of(new AiIssue(RiskLevel.HIGH, "DELETE_WITHOUT_WHERE", "msg", "fix")),
                false, 1000L, AiProviderType.ANTHROPIC, "claude-sonnet-4-20250514", 100, 50);
        when(strategy.analyze(any(), any(), any(), any())).thenReturn(result);

        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                eventPublisher.publishEvent(new QuerySubmittedEvent(queryRequestId)));

        Awaitility.await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> {
                    var analysis = aiAnalysisRepository.findByQueryRequest_Id(queryRequestId);
                    assertThat(analysis).isPresent();
                    var entity = analysis.get();
                    assertThat(entity.getRiskLevel()).isEqualTo(RiskLevel.HIGH);
                    assertThat(entity.getRiskScore()).isEqualTo(85);
                    assertThat(entity.getAiProvider()).isEqualTo(AiProviderType.ANTHROPIC);
                    assertThat(entity.getAiModel()).isEqualTo("claude-sonnet-4-20250514");
                    assertThat(entity.getPromptTokens()).isEqualTo(100);
                    assertThat(entity.getCompletionTokens()).isEqualTo(50);
                    assertThat(entity.getIssues()).contains("DELETE_WITHOUT_WHERE");
                    var query = queryRequestRepository.findById(queryRequestId).orElseThrow();
                    assertThat(query.getAiAnalysisId()).isEqualTo(entity.getId());
                });
    }

    @Test
    void onSubmittedPersistsSentinelOnStrategyFailure() {
        when(strategy.analyze(any(), any(), any(), any()))
                .thenThrow(new com.partqam.accessflow.ai.api.AiAnalysisException("provider down"));

        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                eventPublisher.publishEvent(new QuerySubmittedEvent(queryRequestId)));

        Awaitility.await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> {
                    var analysis = aiAnalysisRepository.findByQueryRequest_Id(queryRequestId);
                    assertThat(analysis).isPresent();
                    assertThat(analysis.get().getRiskLevel()).isEqualTo(RiskLevel.CRITICAL);
                    assertThat(analysis.get().getRiskScore()).isEqualTo(100);
                    assertThat(analysis.get().getSummary()).startsWith("AI analysis failed:");
                });
    }
}
