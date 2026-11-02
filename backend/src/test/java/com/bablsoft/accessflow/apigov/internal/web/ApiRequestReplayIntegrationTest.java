package com.bablsoft.accessflow.apigov.internal.web;

import com.bablsoft.accessflow.TestcontainersConfig;
import com.bablsoft.accessflow.apigov.api.ApiAuthMethod;
import com.bablsoft.accessflow.apigov.api.ApiBodyType;
import com.bablsoft.accessflow.apigov.api.ApiProtocol;
import com.bablsoft.accessflow.apigov.internal.persistence.entity.ApiConnectorEntity;
import com.bablsoft.accessflow.apigov.internal.persistence.entity.ApiConnectorUserPermissionEntity;
import com.bablsoft.accessflow.apigov.internal.persistence.entity.ApiRequestEntity;
import com.bablsoft.accessflow.apigov.internal.persistence.repo.ApiConnectorRepository;
import com.bablsoft.accessflow.apigov.internal.persistence.repo.ApiConnectorUserPermissionRepository;
import com.bablsoft.accessflow.apigov.internal.persistence.repo.ApiRequestRepository;
import com.bablsoft.accessflow.audit.api.AuditAction;
import com.bablsoft.accessflow.core.api.AuthProviderType;
import com.bablsoft.accessflow.core.api.QueryStatus;
import com.bablsoft.accessflow.core.api.SubmissionReason;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.web.context.WebApplicationContext;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

/**
 * Replay end to end (#886): a terminal API request becomes a new governed submission owned by the
 * caller, the source row is left alone, and the replay produces exactly one audit row.
 */
@SpringBootTest
@ImportTestcontainers(TestcontainersConfig.class)
class ApiRequestReplayIntegrationTest {

    @Autowired WebApplicationContext context;
    @Autowired OrganizationRepository organizationRepository;
    @Autowired UserRepository userRepository;
    @Autowired ApiConnectorRepository connectorRepository;
    @Autowired ApiConnectorUserPermissionRepository permissionRepository;
    @Autowired ApiRequestRepository requestRepository;
    @Autowired JwtService jwtService;
    @Autowired JdbcTemplate jdbcTemplate;

    private MockMvcTester mvc;
    private OrganizationEntity org;
    private ApiConnectorEntity connector;
    private UserEntity analyst;
    private UserEntity otherAnalyst;
    private String analystToken;
    private String otherAnalystToken;

    @BeforeEach
    void setUp() {
        mvc = MockMvcTester.from(context, builder -> builder.apply(springSecurity()).build());
        var suffix = UUID.randomUUID().toString().substring(0, 8);
        org = saveOrg("ApiReplay " + suffix, "api-replay-" + suffix);
        analyst = saveUser("analyst-replay-" + suffix + "@example.com");
        otherAnalyst = saveUser("other-replay-" + suffix + "@example.com");
        connector = saveConnector("replay-api-" + suffix);
        givenPermission(analyst);
        analystToken = generateToken(analyst);
        otherAnalystToken = generateToken(otherAnalyst);
    }

    /** Scoped to this class's own rows — the Testcontainers database is shared. */
    @AfterEach
    void cleanup() {
        if (connector == null) {
            return;
        }
        jdbcTemplate.update("delete from audit_log where organization_id = ?", org.getId());
        jdbcTemplate.update("delete from ai_analyses where api_request_id in "
                + "(select id from api_requests where organization_id = ?)", org.getId());
        jdbcTemplate.update("delete from api_requests where organization_id = ?", org.getId());
        permissionRepository.deleteAll(permissionRepository.findByConnectorId(connector.getId()));
        connectorRepository.deleteById(connector.getId());
        userRepository.deleteAllById(List.of(analyst.getId(), otherAnalyst.getId()));
        organizationRepository.deleteById(org.getId());
        connector = null;
    }

    @Test
    void replayCreatesANewSubmissionOwnedByTheCallerWithOneAuditRow() {
        var source = saveSource(QueryStatus.EXECUTED);

        var result = mvc.post().uri("/api/v1/api-requests/{id}/replay", source.getId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + analystToken)
                .exchange();

        assertThat(result).hasStatus(202);
        assertThat(result).bodyJson().extractingPath("$.submitted_by").asString()
                .isEqualTo(analyst.getId().toString());
        assertThat(result).bodyJson().extractingPath("$.request_path").asString().isEqualTo("/charges");
        // No can_override_variables on the grant: the stored override is dropped and counted.
        assertThat(result).bodyJson().extractingPath("$.dropped_variable_override_count").asNumber()
                .isEqualTo(1);
        assertThat(result).bodyJson().doesNotHavePath("$.request");

        var rows = requestRepository.findAll().stream()
                .filter(r -> org.getId().equals(r.getOrganizationId()))
                .filter(r -> !r.getId().equals(source.getId()))
                .toList();
        assertThat(rows).hasSize(1);
        var replayed = rows.get(0);
        assertThat(replayed.getSubmittedBy()).isEqualTo(analyst.getId());
        assertThat(replayed.getScheduledFor()).isNull();
        assertThat(replayed.getSubmissionReason()).isEqualTo(SubmissionReason.USER_SUBMITTED);
        assertThat(replayed.getVariableOverrides()).isEqualTo("{}");
        assertThat(requestRepository.findById(source.getId()).orElseThrow().getStatus())
                .isEqualTo(QueryStatus.EXECUTED);

        var audit = jdbcTemplate.queryForList(
                "select action, resource_id, metadata::text as metadata from audit_log "
                        + "where organization_id = ? and action = ?",
                org.getId(), AuditAction.API_REQUEST_SUBMITTED.name());
        assertThat(audit).hasSize(1);
        assertThat(audit.get(0).get("resource_id")).hasToString(replayed.getId().toString());
        assertThat(String.valueOf(audit.get(0).get("metadata")))
                .contains("\"trigger\": \"replay\"")
                .contains(source.getId().toString());
    }

    @Test
    void aNonTerminalSourceIsAConflict() {
        var source = saveSource(QueryStatus.PENDING_REVIEW);

        var result = mvc.post().uri("/api/v1/api-requests/{id}/replay", source.getId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + analystToken)
                .exchange();

        assertThat(result).hasStatus(409);
        assertThat(result).bodyJson().extractingPath("$.error").asString()
                .isEqualTo("API_REQUEST_INVALID_STATE");
    }

    @Test
    void someoneElsesSourceIsNotFound() {
        var source = saveSource(QueryStatus.EXECUTED);

        var result = mvc.post().uri("/api/v1/api-requests/{id}/replay", source.getId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + otherAnalystToken)
                .exchange();

        assertThat(result).hasStatus(404);
        assertThat(result).bodyJson().extractingPath("$.error").asString()
                .isEqualTo("API_REQUEST_NOT_FOUND");
    }

    // ── Fixtures ──────────────────────────────────────────────────────────────

    private ApiRequestEntity saveSource(QueryStatus status) {
        var entity = new ApiRequestEntity();
        entity.setId(UUID.randomUUID());
        entity.setConnectorId(connector.getId());
        entity.setOrganizationId(org.getId());
        entity.setSubmittedBy(analyst.getId());
        entity.setVerb("POST");
        entity.setRequestPath("/charges");
        entity.setBodyType(ApiBodyType.RAW);
        entity.setRequestContentType("application/json");
        entity.setRequestBody("{\"amount\":100}");
        entity.setVariableOverrides("{\"tenant\":\"t1\"}");
        entity.setWrite(true);
        entity.setStatus(status);
        entity.setSubmissionReason(SubmissionReason.EMERGENCY_ACCESS);
        entity.setJustification("original");
        return requestRepository.save(entity);
    }

    private OrganizationEntity saveOrg(String name, String slug) {
        var entity = new OrganizationEntity();
        entity.setId(UUID.randomUUID());
        entity.setName(name);
        entity.setSlug(slug);
        return organizationRepository.save(entity);
    }

    private UserEntity saveUser(String email) {
        var entity = new UserEntity();
        entity.setId(UUID.randomUUID());
        entity.setEmail(email);
        entity.setDisplayName(email);
        entity.setPasswordHash("x");
        entity.setRole(UserRoleType.ANALYST);
        entity.setAuthProvider(AuthProviderType.LOCAL);
        entity.setActive(true);
        entity.setOrganization(org);
        return userRepository.save(entity);
    }

    private ApiConnectorEntity saveConnector(String name) {
        var entity = new ApiConnectorEntity();
        entity.setId(UUID.randomUUID());
        entity.setOrganizationId(org.getId());
        entity.setName(name);
        entity.setProtocol(ApiProtocol.REST);
        entity.setBaseUrl("https://api.invalid");
        entity.setAuthMethod(ApiAuthMethod.NONE);
        entity.setActive(true);
        entity.setRequireReviewReads(false);
        entity.setRequireReviewWrites(true);
        return connectorRepository.save(entity);
    }

    private void givenPermission(UserEntity user) {
        var entity = new ApiConnectorUserPermissionEntity();
        entity.setId(UUID.randomUUID());
        entity.setConnectorId(connector.getId());
        entity.setUserId(user.getId());
        entity.setCanRead(true);
        entity.setCanWrite(true);
        entity.setCreatedBy(user.getId());
        permissionRepository.save(entity);
    }

    private String generateToken(UserEntity entity) {
        var view = new com.bablsoft.accessflow.core.api.UserView(
                entity.getId(), entity.getEmail(), entity.getDisplayName(), entity.getRole(),
                entity.getOrganization().getId(), entity.isActive(), entity.getAuthProvider(),
                entity.getPasswordHash(), entity.getLastLoginAt(), entity.getPreferredLanguage(),
                entity.isTotpEnabled(), entity.getCreatedAt());
        return jwtService.generateAccessToken(view);
    }
}
