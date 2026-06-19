package com.bablsoft.accessflow.workflow.internal.web;

import com.bablsoft.accessflow.TestcontainersConfig;
import com.bablsoft.accessflow.audit.api.AuditAction;
import com.bablsoft.accessflow.audit.api.AuditEntry;
import com.bablsoft.accessflow.audit.api.AuditLogService;
import com.bablsoft.accessflow.core.api.AuthProviderType;
import com.bablsoft.accessflow.core.api.DbType;
import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.core.internal.persistence.entity.OrganizationEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.UserEntity;
import com.bablsoft.accessflow.core.internal.persistence.repo.OrganizationRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.UserRepository;
import com.bablsoft.accessflow.security.internal.jwt.JwtService;
import com.bablsoft.accessflow.workflow.api.QueryReplayService;
import com.bablsoft.accessflow.workflow.api.QueryReplayService.ReplayResult;
import com.bablsoft.accessflow.workflow.api.QuerySnapshotNotFoundException;
import com.bablsoft.accessflow.workflow.api.ReplaySchemaIncompatibleException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.http.HttpHeaders;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

@SpringBootTest
@ImportTestcontainers(TestcontainersConfig.class)
class QueryReplayControllerIntegrationTest {

    @Autowired WebApplicationContext context;
    @Autowired UserRepository userRepository;
    @Autowired OrganizationRepository organizationRepository;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired JwtService jwtService;
    @MockitoBean QueryReplayService queryReplayService;
    @MockitoBean AuditLogService auditLogService;

    private MockMvcTester mvc;
    private String analystToken;
    private final UUID originalQueryId = UUID.randomUUID();
    private final UUID targetDsId = UUID.randomUUID();
    private final UUID sourceDsId = UUID.randomUUID();

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

        var analyst = new UserEntity();
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
                null, null, false, Instant.now()));
    }

    @AfterEach
    void cleanup() {
        userRepository.deleteAll();
        organizationRepository.deleteAll();
    }

    @Test
    void replayReturns202AndAuditsTriggerReplay() {
        var newId = UUID.randomUUID();
        when(queryReplayService.replay(any())).thenReturn(new ReplayResult(
                newId, QueryStatus.PENDING_AI, "src-hash", "tgt-hash", sourceDsId, targetDsId));

        var response = mvc.post().uri("/api/v1/queries/{id}/replay?targetDatasourceId={t}",
                        originalQueryId, targetDsId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + analystToken)
                .exchange();

        assertThat(response).hasStatus(202);
        assertThat(response).bodyJson().extractingPath("$.id").asString().isEqualTo(newId.toString());
        assertThat(response).bodyJson().extractingPath("$.status").asString().isEqualTo("PENDING_AI");

        var captor = ArgumentCaptor.forClass(AuditEntry.class);
        verify(auditLogService).record(captor.capture());
        var entry = captor.getValue();
        assertThat(entry.action()).isEqualTo(AuditAction.QUERY_SUBMITTED);
        assertThat(entry.resourceId()).isEqualTo(newId);
        assertThat(entry.metadata()).containsEntry("trigger", "replay");
        assertThat(entry.metadata()).containsEntry("original_query_id", originalQueryId.toString());
        assertThat(entry.metadata()).containsEntry("source_datasource_id", sourceDsId.toString());
        assertThat(entry.metadata()).containsEntry("target_datasource_id", targetDsId.toString());
        assertThat(entry.metadata()).containsEntry("source_schema_hash", "src-hash");
        assertThat(entry.metadata()).containsEntry("target_schema_hash", "tgt-hash");
    }

    @Test
    void replayWithoutTokenReturns401() {
        var response = mvc.post().uri("/api/v1/queries/{id}/replay?targetDatasourceId={t}",
                        originalQueryId, targetDsId)
                .exchange();

        assertThat(response).hasStatus(401);
    }

    @Test
    void replayReturns404WhenSnapshotMissing() {
        when(queryReplayService.replay(any()))
                .thenThrow(new QuerySnapshotNotFoundException(originalQueryId));

        var response = mvc.post().uri("/api/v1/queries/{id}/replay?targetDatasourceId={t}",
                        originalQueryId, targetDsId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + analystToken)
                .exchange();

        assertThat(response).hasStatus(404);
        assertThat(response).bodyJson().extractingPath("$.error").asString()
                .isEqualTo("QUERY_SNAPSHOT_NOT_FOUND");
    }

    @Test
    void replayReturns422WhenSchemaIncompatible() {
        when(queryReplayService.replay(any())).thenThrow(
                ReplaySchemaIncompatibleException.missingTables(targetDsId, List.of("public.payments")));

        var response = mvc.post().uri("/api/v1/queries/{id}/replay?targetDatasourceId={t}",
                        originalQueryId, targetDsId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + analystToken)
                .exchange();

        assertThat(response).hasStatus(422);
        assertThat(response).bodyJson().extractingPath("$.error").asString()
                .isEqualTo("REPLAY_SCHEMA_INCOMPATIBLE");
        assertThat(response).bodyJson().extractingPath("$.missing_tables").asArray()
                .containsExactly("public.payments");
    }

    @Test
    void replayReturns422WhenDbTypeMismatch() {
        when(queryReplayService.replay(any())).thenThrow(
                ReplaySchemaIncompatibleException.dbTypeMismatch(targetDsId, DbType.POSTGRESQL, DbType.MYSQL));

        var response = mvc.post().uri("/api/v1/queries/{id}/replay?targetDatasourceId={t}",
                        originalQueryId, targetDsId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + analystToken)
                .exchange();

        assertThat(response).hasStatus(422);
        assertThat(response).bodyJson().extractingPath("$.error").asString()
                .isEqualTo("REPLAY_SCHEMA_INCOMPATIBLE");
    }
}
