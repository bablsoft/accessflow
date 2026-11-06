package com.bablsoft.accessflow.apigov.internal.web;

import com.bablsoft.accessflow.TestcontainersConfig;
import com.bablsoft.accessflow.apigov.api.ApiAuthMethod;
import com.bablsoft.accessflow.apigov.api.ApiProtocol;
import com.bablsoft.accessflow.apigov.api.ApiSchemaRevisionService;
import com.bablsoft.accessflow.apigov.internal.persistence.entity.ApiConnectorEntity;
import com.bablsoft.accessflow.apigov.internal.persistence.entity.ApiConnectorUserPermissionEntity;
import com.bablsoft.accessflow.apigov.internal.persistence.repo.ApiConnectorRepository;
import com.bablsoft.accessflow.apigov.internal.persistence.repo.ApiConnectorUserPermissionRepository;
import com.bablsoft.accessflow.apigov.internal.persistence.repo.ApiSchemaRepository;
import com.bablsoft.accessflow.apigov.internal.scheduled.ApiSchemaRefreshJob;
import com.bablsoft.accessflow.core.api.AuthProviderType;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.core.internal.persistence.entity.OrganizationEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.UserEntity;
import com.bablsoft.accessflow.core.internal.persistence.repo.OrganizationRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.UserRepository;
import com.bablsoft.accessflow.security.internal.jwt.JwtService;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.web.context.WebApplicationContext;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

/**
 * Schema drift end to end (#893): upload by {@code sourceUrl}, the upstream document changes, the
 * refresh job records a revision and findings, and the governed catalog stays put until an admin
 * accepts the revision.
 */
@SpringBootTest
@ImportTestcontainers(TestcontainersConfig.class)
class ApiSchemaRefreshIntegrationTest {

    private static final String V1 = """
            {"openapi":"3.0.0","info":{"title":"Pets","version":"1"},"paths":{
              "/pets":{"get":{"operationId":"listPets","responses":{"200":{"description":"ok"}}},
                       "post":{"operationId":"createPet","responses":{"201":{"description":"ok"}}}}}}
            """;
    private static final String V2 = """
            {"openapi":"3.0.0","info":{"title":"Pets","version":"2"},"paths":{
              "/pets":{"get":{"operationId":"listPets","responses":{"200":{"description":"ok"}}}},
              "/pets/{id}":{"delete":{"operationId":"deletePet","responses":{"204":{"description":"ok"}}}}}}
            """;

    @Autowired WebApplicationContext context;
    @Autowired OrganizationRepository organizationRepository;
    @Autowired UserRepository userRepository;
    @Autowired ApiConnectorRepository connectorRepository;
    @Autowired ApiConnectorUserPermissionRepository permissionRepository;
    @Autowired ApiSchemaRepository schemaRepository;
    @Autowired ApiSchemaRevisionService revisionService;
    @Autowired Clock clock;
    @Autowired JwtService jwtService;
    @Autowired JdbcTemplate jdbcTemplate;

    private MockMvcTester mvc;
    private HttpServer upstream;
    private final AtomicReference<String> served = new AtomicReference<>(V1);
    private final AtomicInteger upstreamStatus = new AtomicInteger(200);
    private ApiSchemaRefreshJob job;
    private OrganizationEntity org;
    private UserEntity admin;
    private UserEntity analyst;
    private final List<ApiConnectorEntity> connectors = new ArrayList<>();
    private String adminToken;
    private String analystToken;
    private String sourceUrl;

    @BeforeEach
    void setUp() throws IOException {
        mvc = MockMvcTester.from(context, builder -> builder.apply(springSecurity()).build());
        upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext("/openapi.json", exchange -> {
            var body = served.get().getBytes(StandardCharsets.UTF_8);
            if (upstreamStatus.get() != 200) {
                exchange.sendResponseHeaders(upstreamStatus.get(), -1);
            } else {
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
            exchange.close();
        });
        upstream.start();
        sourceUrl = "http://127.0.0.1:" + upstream.getAddress().getPort() + "/openapi.json";
        // Built directly rather than through the context's proxy: ShedLock's lockAtLeastFor would
        // otherwise skip every run after the first, across all classes sharing the test Redis.
        job = new ApiSchemaRefreshJob(schemaRepository, revisionService, clock);

        var suffix = UUID.randomUUID().toString().substring(0, 8);
        org = new OrganizationEntity();
        org.setId(UUID.randomUUID());
        org.setName("Drift " + suffix);
        org.setSlug("drift-" + suffix);
        org = organizationRepository.save(org);
        admin = saveUser("admin-drift-" + suffix + "@example.com", UserRoleType.ADMIN);
        analyst = saveUser("analyst-drift-" + suffix + "@example.com", UserRoleType.ANALYST);
        adminToken = token(admin);
        analystToken = token(analyst);
    }

    @AfterEach
    void cleanup() {
        upstream.stop(0);
        jdbcTemplate.update("delete from audit_log where organization_id = ?", org.getId());
        for (var connector : connectors) {
            schemaRepository.deleteAll(schemaRepository.findByConnectorIdOrderByCreatedAtDesc(connector.getId()));
            permissionRepository.deleteAll(permissionRepository.findByConnectorId(connector.getId()));
            connectorRepository.deleteById(connector.getId());
        }
        userRepository.deleteAllById(List.of(admin.getId(), analyst.getId()));
        organizationRepository.deleteById(org.getId());
    }

    @Test
    void driftIsRecordedButTheCatalogOnlyChangesOnAccept() {
        var connector = saveConnector(true);
        var schemaId = upload(connector, 5);
        grant(connector, "createPet");
        assertThat(operationIds(connector)).containsExactlyInAnyOrder("listPets", "createPet");

        served.set(V2);
        job.run();

        var revisions = get("/api/v1/api-connectors/%s/schemas/%s/revisions".formatted(connector.getId(), schemaId),
                adminToken);
        assertThat(revisions).hasStatus(200);
        assertThat(revisions).bodyJson().extractingPath("$.length()").asNumber().isEqualTo(1);
        var revisionId = jdbcTemplate.queryForObject("select id from api_schema_revisions where schema_id = ?",
                UUID.class, schemaId);

        var open = get("/api/v1/api-connectors/%s/drift-findings?status=OPEN".formatted(connector.getId()),
                adminToken);
        assertThat(open).hasStatus(200);
        assertThat(open).bodyJson().extractingPath("$.length()").asNumber().isEqualTo(2);
        assertThat(open).bodyJson().extractingPath("$[?(@.kind == 'OPERATION_REMOVED')].operation_id").asArray()
                .containsExactly("createPet");
        assertThat(open).bodyJson().extractingPath("$[?(@.kind == 'OPERATION_REMOVED')].affected_grant_count")
                .asArray().containsExactly(1);
        assertThat(open).bodyJson().extractingPath("$[?(@.kind == 'OPERATION_ADDED')].operation_id").asArray()
                .containsExactly("deletePet");

        // Untouched until a human accepts.
        assertThat(operationIds(connector)).containsExactlyInAnyOrder("listPets", "createPet");

        // A second pass over the same upstream document is deduplicated.
        assertThat(revisionService.refresh(connector.getId(), org.getId(), schemaId).unchanged()).isTrue();

        var accept = post("/api/v1/api-connectors/%s/schemas/%s/revisions/%s/accept"
                .formatted(connector.getId(), schemaId, revisionId), adminToken);
        assertThat(accept).hasStatus(200);
        assertThat(accept).bodyJson().extractingPath("$.id").asString().isEqualTo(schemaId.toString());
        assertThat(accept).bodyJson().extractingPath("$.current_revision_id").asString()
                .isEqualTo(revisionId.toString());
        assertThat(operationIds(connector)).containsExactlyInAnyOrder("listPets", "deletePet");
        assertThat(get("/api/v1/api-connectors/%s/drift-findings?status=ACCEPTED".formatted(connector.getId()),
                adminToken)).bodyJson().extractingPath("$.length()").asNumber().isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from audit_log where organization_id = ? and action = 'API_SCHEMA_REVISION_ACCEPTED'",
                Integer.class, org.getId())).isEqualTo(1);
        // The grant itself is never touched.
        assertThat(permissionRepository.findByConnectorId(connector.getId()).getFirst().getAllowedOperations())
                .containsExactly("createPet");

        assertThat(post("/api/v1/api-connectors/%s/schemas/%s/revisions/%s/accept"
                .formatted(connector.getId(), schemaId, revisionId), adminToken)).hasStatus(409);
    }

    @Test
    void anUpstreamRollbackAndRedeployIsReportedEveryTime() {
        var connector = saveConnector(true);
        var schemaId = upload(connector, 5);

        served.set(V2);
        var v2 = revisionService.refresh(connector.getId(), org.getId(), schemaId);
        assertThat(v2.findingCount()).isEqualTo(2);
        assertThat(accept(connector, schemaId, v2.revisionId())).hasStatus(200);

        served.set(V1);
        var v1 = revisionService.refresh(connector.getId(), org.getId(), schemaId);
        assertThat(v1.unchanged()).isFalse();
        assertThat(v1.findingCount()).isEqualTo(2);
        assertThat(accept(connector, schemaId, v1.revisionId())).hasStatus(200);
        assertThat(operationIds(connector)).containsExactlyInAnyOrder("listPets", "createPet");

        // The v2 revision row already exists and was accepted once — it must still be reported and
        // be acceptable again, or the catalog would silently stay on v1 while the upstream serves v2.
        served.set(V2);
        var redeploy = revisionService.refresh(connector.getId(), org.getId(), schemaId);
        assertThat(redeploy.revisionId()).isEqualTo(v2.revisionId());
        assertThat(redeploy.findingCount()).isEqualTo(2);
        assertThat(revisionService.refresh(connector.getId(), org.getId(), schemaId).unchanged()).isTrue();
        assertThat(accept(connector, schemaId, v2.revisionId())).hasStatus(200);
        assertThat(operationIds(connector)).containsExactlyInAnyOrder("listPets", "deletePet");
        assertThat(accept(connector, schemaId, v2.revisionId())).hasStatus(409);

        // Back on the governed document: nothing new, and open drift is resolved.
        served.set(V1);
        revisionService.refresh(connector.getId(), org.getId(), schemaId);
        served.set(V2);
        assertThat(revisionService.refresh(connector.getId(), org.getId(), schemaId).unchanged()).isTrue();
        assertThat(get("/api/v1/api-connectors/%s/drift-findings?status=OPEN".formatted(connector.getId()),
                adminToken)).bodyJson().extractingPath("$.length()").asNumber().isEqualTo(0);
    }

    @Test
    void anUpstreamFailureIsRecordedAndProducesNoRevision() {
        var connector = saveConnector(true);
        var schemaId = upload(connector, 5);
        upstreamStatus.set(502);

        var refresh = post("/api/v1/api-connectors/%s/schemas/%s/refresh".formatted(connector.getId(), schemaId),
                adminToken);

        assertThat(refresh).hasStatus(422);
        assertThat(refresh).bodyJson().extractingPath("$.error").asString().isEqualTo("API_SCHEMA_FETCH_ERROR");
        var schema = schemaRepository.findById(schemaId).orElseThrow();
        assertThat(schema.getLastRefreshError()).contains("502");
        assertThat(schema.getLastRefreshAt()).isNotNull();
        assertThat(jdbcTemplate.queryForObject("select count(*) from api_schema_revisions where schema_id = ?",
                Integer.class, schemaId)).isZero();
    }

    @Test
    void theJobSkipsOptedOutSchemasAndInactiveConnectors() {
        var active = saveConnector(true);
        var optedOut = upload(active, null);
        var inactive = saveConnector(false);
        var onInactive = upload(inactive, 5);
        served.set(V2);

        job.run();

        assertThat(schemaRepository.findById(optedOut).orElseThrow().getLastRefreshAt()).isNull();
        assertThat(schemaRepository.findById(onInactive).orElseThrow().getLastRefreshAt()).isNull();
    }

    @Test
    void refreshPolicyNeedsASourceUrlAndTheEndpointsNeedTheManagePermission() {
        var connector = saveConnector(true);
        var pasted = mvc.post().uri("/api/v1/api-connectors/%s/schemas".formatted(connector.getId()))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"schema_type":"OPENAPI","raw_content":%s}
                        """.formatted(quote(V1)))
                .exchange();
        assertThat(pasted).hasStatus(201);
        var pastedId = jdbcTemplate.queryForObject("select id from api_schemas where connector_id = ?",
                UUID.class, connector.getId());

        var policy = mvc.put().uri("/api/v1/api-connectors/%s/schemas/%s/refresh-policy"
                        .formatted(connector.getId(), pastedId))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"refresh_interval_minutes\":10}")
                .exchange();
        assertThat(policy).hasStatus(422);
        assertThat(policy).bodyJson().extractingPath("$.error").asString().isEqualTo("API_SCHEMA_NOT_REFRESHABLE");

        assertThat(get("/api/v1/api-connectors/%s/drift-findings".formatted(connector.getId()), analystToken))
                .hasStatus(403);
        assertThat(post("/api/v1/api-connectors/%s/schemas/%s/refresh".formatted(connector.getId(), pastedId),
                analystToken)).hasStatus(403);
    }

    private UUID upload(ApiConnectorEntity connector, Integer refreshIntervalMinutes) {
        var interval = refreshIntervalMinutes == null ? "" : ",\"refresh_interval_minutes\":" + refreshIntervalMinutes;
        var result = mvc.post().uri("/api/v1/api-connectors/%s/schemas".formatted(connector.getId()))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"schema_type\":\"OPENAPI\",\"raw_content\":\"\",\"source_url\":\"%s\"%s}"
                        .formatted(sourceUrl, interval))
                .exchange();
        assertThat(result).hasStatus(201);
        return schemaRepository.findFirstByConnectorIdOrderByCreatedAtDesc(connector.getId()).orElseThrow().getId();
    }

    private org.springframework.test.web.servlet.assertj.MvcTestResult accept(ApiConnectorEntity connector,
                                                                         UUID schemaId, UUID revisionId) {
        return post("/api/v1/api-connectors/%s/schemas/%s/revisions/%s/accept"
                .formatted(connector.getId(), schemaId, revisionId), adminToken);
    }

    private List<String> operationIds(ApiConnectorEntity connector) {
        var result = get("/api/v1/api-connectors/%s/operations".formatted(connector.getId()), adminToken);
        assertThat(result).hasStatus(200);
        var ids = new ArrayList<String>();
        assertThat(result).bodyJson().extractingPath("$[*].operation_id").asArray()
                .satisfies(list -> java.util.Arrays.stream(list).forEach(id -> ids.add(String.valueOf(id))));
        return ids;
    }

    private org.springframework.test.web.servlet.assertj.MvcTestResult get(String uri, String token) {
        return mvc.get().uri(uri).header(HttpHeaders.AUTHORIZATION, "Bearer " + token).exchange();
    }

    private org.springframework.test.web.servlet.assertj.MvcTestResult post(String uri, String token) {
        return mvc.post().uri(uri).header(HttpHeaders.AUTHORIZATION, "Bearer " + token).exchange();
    }

    private void grant(ApiConnectorEntity connector, String operationId) {
        var entity = new ApiConnectorUserPermissionEntity();
        entity.setId(UUID.randomUUID());
        entity.setConnectorId(connector.getId());
        entity.setUserId(analyst.getId());
        entity.setCanRead(true);
        entity.setCanWrite(true);
        entity.setAllowedOperations(new String[] {operationId});
        entity.setCreatedBy(admin.getId());
        permissionRepository.save(entity);
    }

    private ApiConnectorEntity saveConnector(boolean active) {
        var entity = new ApiConnectorEntity();
        entity.setId(UUID.randomUUID());
        entity.setOrganizationId(org.getId());
        entity.setName("pets-" + UUID.randomUUID().toString().substring(0, 8));
        entity.setProtocol(ApiProtocol.REST);
        entity.setBaseUrl("https://api.invalid");
        entity.setAuthMethod(ApiAuthMethod.NONE);
        entity.setActive(active);
        entity.setRequireReviewReads(false);
        entity.setRequireReviewWrites(true);
        var saved = connectorRepository.save(entity);
        connectors.add(saved);
        return saved;
    }

    private UserEntity saveUser(String email, UserRoleType role) {
        var entity = new UserEntity();
        entity.setId(UUID.randomUUID());
        entity.setEmail(email);
        entity.setDisplayName(email);
        entity.setPasswordHash("x");
        entity.setRole(role);
        entity.setAuthProvider(AuthProviderType.LOCAL);
        entity.setActive(true);
        entity.setOrganization(org);
        return userRepository.save(entity);
    }

    private String token(UserEntity entity) {
        var view = new com.bablsoft.accessflow.core.api.UserView(
                entity.getId(), entity.getEmail(), entity.getDisplayName(), entity.getRole(),
                entity.getOrganization().getId(), entity.isActive(), entity.getAuthProvider(),
                entity.getPasswordHash(), entity.getLastLoginAt(), entity.getPreferredLanguage(),
                entity.isTotpEnabled(), entity.getCreatedAt());
        return jwtService.generateAccessToken(view);
    }

    private static String quote(String raw) {
        return "\"" + raw.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }
}
