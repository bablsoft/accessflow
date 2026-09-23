package com.bablsoft.accessflow.schemachange.internal.web;

import com.bablsoft.accessflow.TestcontainersConfig;
import com.bablsoft.accessflow.core.api.AuthProviderType;
import com.bablsoft.accessflow.core.api.CredentialEncryptionService;
import com.bablsoft.accessflow.core.api.DatasourceEnvironment;
import com.bablsoft.accessflow.core.api.DbType;
import com.bablsoft.accessflow.core.api.SslMode;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.core.api.UserView;
import com.bablsoft.accessflow.core.internal.persistence.entity.DatasourceEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.OrganizationEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.UserEntity;
import com.bablsoft.accessflow.core.internal.persistence.repo.DatasourceRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.OrganizationRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.UserRepository;
import com.bablsoft.accessflow.deploygov.api.PipelineProvider;
import com.bablsoft.accessflow.deploygov.internal.persistence.entity.DeploymentEnvironmentEntity;
import com.bablsoft.accessflow.deploygov.internal.persistence.entity.DeploymentPipelineEntity;
import com.bablsoft.accessflow.deploygov.internal.persistence.repo.DeploymentEnvironmentRepository;
import com.bablsoft.accessflow.deploygov.internal.persistence.repo.DeploymentPipelineRepository;
import com.bablsoft.accessflow.schemachange.api.SchemaChangePromotionStatus;
import com.bablsoft.accessflow.schemachange.internal.persistence.entity.SchemaChangeSetPromotionEntity;
import com.bablsoft.accessflow.schemachange.internal.persistence.repo.SchemaChangeSetPromotionRepository;
import com.bablsoft.accessflow.schemachange.internal.persistence.repo.SchemaChangeSetRepository;
import com.bablsoft.accessflow.security.internal.jwt.JwtService;
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
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.web.context.WebApplicationContext;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

/**
 * The authoring surface end to end (#879) over the real engine-aware parser, the real deterministic
 * SQL review resolution and the real PG-enum bindings: the "not DML" gate, the shape checks, a
 * ruleset BLOCK refusing the save, WARN findings on the write response only, the freeze and archive
 * guards, the status filter, the cap and the permission gate.
 */
@SpringBootTest
@ImportTestcontainers(TestcontainersConfig.class)
class SchemaChangeSetControllerIntegrationTest {

    private static final String BASE = "/api/v1/schema-change-sets";

    @Autowired WebApplicationContext context;
    @Autowired OrganizationRepository organizationRepository;
    @Autowired UserRepository userRepository;
    @Autowired DatasourceRepository datasourceRepository;
    @Autowired DeploymentPipelineRepository pipelineRepository;
    @Autowired DeploymentEnvironmentRepository environmentRepository;
    @Autowired SchemaChangeSetRepository changeSetRepository;
    @Autowired SchemaChangeSetPromotionRepository promotionRepository;
    @Autowired CredentialEncryptionService encryptionService;
    @Autowired JwtService jwtService;
    @Autowired JdbcTemplate jdbcTemplate;

    private MockMvcTester mvc;
    private final String suffix = UUID.randomUUID().toString().substring(0, 8);
    private OrganizationEntity org;
    private OrganizationEntity otherOrg;
    private UserEntity admin;
    private UserEntity analyst;
    private DatasourceEntity datasource;
    private DeploymentPipelineEntity pipeline;
    private DeploymentPipelineEntity unboundPipeline;
    private DeploymentPipelineEntity foreignPipeline;
    private String adminToken;
    private String analystToken;

    @BeforeEach
    void setUp() {
        mvc = MockMvcTester.from(context, builder -> builder.apply(springSecurity()).build());
        org = saveOrg("SchemaChange " + suffix, "schemachange-" + suffix);
        otherOrg = saveOrg("SchemaChange other " + suffix, "schemachange-other-" + suffix);
        admin = saveUser(org, "admin-sc-" + suffix, UserRoleType.ADMIN);
        analyst = saveUser(org, "analyst-sc-" + suffix, UserRoleType.ANALYST);
        datasource = saveDatasource(org);
        pipeline = savePipeline(org, "orders-" + suffix);
        saveEnvironment(pipeline, "dev", 0, null);
        saveEnvironment(pipeline, "production", 1, datasource.getId());
        unboundPipeline = savePipeline(org, "unbound-" + suffix);
        saveEnvironment(unboundPipeline, "dev", 0, null);
        foreignPipeline = savePipeline(otherOrg, "foreign-" + suffix);
        adminToken = generateToken(admin);
        analystToken = generateToken(analyst);
    }

    /** Scoped to this class's own rows — the Testcontainers database is shared. */
    @AfterEach
    void cleanup() {
        if (org == null) {
            return;
        }
        for (var o : List.of(org, otherOrg)) {
            jdbcTemplate.update("delete from schema_change_sets where organization_id = ?", o.getId());
            jdbcTemplate.update("delete from sql_review_rulesets where organization_id = ?", o.getId());
            jdbcTemplate.update("delete from audit_log where organization_id = ?", o.getId());
        }
        for (var p : List.of(pipeline, unboundPipeline, foreignPipeline)) {
            environmentRepository.deleteAll(environmentRepository.findByPipelineIdOrderBySortOrderAscNameAsc(p.getId()));
            pipelineRepository.deleteById(p.getId());
        }
        datasourceRepository.deleteById(datasource.getId());
        userRepository.deleteAllById(List.of(admin.getId(), analyst.getId()));
        organizationRepository.deleteAllById(List.of(org.getId(), otherOrg.getId()));
        org = null;
    }

    // ── Gate ──────────────────────────────────────────────────────────────────

    @Test
    void createsWithDdlAndOtherStatementsNormalisedAndChecksummed() {
        var result = post(adminToken, createBody(pipeline.getId(), "orders-archive",
                "  CREATE TABLE orders_archive (id INT); ",
                "ALTER TABLE orders_archive ADD COLUMN archived_at TIMESTAMP",
                "GRANT SELECT ON orders_archive TO reporting",
                "ALTER TYPE mood ADD VALUE 'sad'"));

        assertThat(result).hasStatus(201);
        assertThat(result).bodyJson().extractingPath("$.status").asString().isEqualTo("DRAFT");
        assertThat(result).bodyJson().extractingPath("$.created_by").asString().isEqualTo(admin.getId().toString());
        assertThat(result).bodyJson().extractingPath("$.statements_checksum").asString().matches("[0-9a-f]{64}");
        assertThat(result).bodyJson().extractingPath("$.statements[0].sequence_order").asNumber().isEqualTo(0);
        assertThat(result).bodyJson().extractingPath("$.statements[0].sql_text").asString()
                .isEqualTo("CREATE TABLE orders_archive (id INT)");
        assertThat(result).bodyJson().extractingPath("$.statements[0].query_type").asString().isEqualTo("DDL");
        assertThat(result).bodyJson().extractingPath("$.statements[1].query_type").asString().isEqualTo("DDL");
        assertThat(result).bodyJson().extractingPath("$.statements[2].sequence_order").asNumber().isEqualTo(2);
        assertThat(result).bodyJson().extractingPath("$.statements[2].query_type").asString().isEqualTo("OTHER");
        // The V91__add_auditor_role.sql shape the epic calls out — JSqlParser 5.4 parses it as ALTER TYPE.
        assertThat(result).bodyJson().extractingPath("$.statements[3].query_type").asString().isEqualTo("DDL");
        // No ruleset is bound in this organisation yet, so the review is clean.
        assertThat(result).bodyJson().extractingPath("$.review_warnings.length()").asNumber().isEqualTo(0);

        var id = idOf(result);
        var read = mvc.get().uri(BASE + "/" + id).header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken).exchange();
        assertThat(read).hasStatus(200);
        assertThat(read).bodyJson().extractingPath("$.statements.length()").asNumber().isEqualTo(4);
        assertThat(read).bodyJson().extractingPath("$.review_warnings.length()").asNumber().isEqualTo(0);
    }

    @Test
    void dmlUnparseableEnvelopeAndMultiStatementTextAreRefusedWithTheirOwnCodes() {
        var dml = post(adminToken, createBody(pipeline.getId(), "dml",
                "CREATE TABLE t (id INT)", "DELETE FROM orders WHERE id = 1"));
        assertThat(dml).hasStatus(422);
        assertThat(dml).bodyJson().extractingPath("$.error").asString().isEqualTo("SCHEMA_CHANGE_SET_STATEMENT_DML");
        assertThat(dml).bodyJson().extractingPath("$.statementIndex").asNumber().isEqualTo(1);
        assertThat(dml).bodyJson().extractingPath("$.queryType").asString().isEqualTo("DELETE");
        assertThat(dml).bodyJson().extractingPath("$.detail").asString().contains("Statement 2");

        var garbage = post(adminToken, createBody(pipeline.getId(), "garbage",
                "DO $$ BEGIN RAISE NOTICE 'x'; END $$"));
        assertThat(garbage).hasStatus(422);
        assertThat(garbage).bodyJson().extractingPath("$.error").asString()
                .isEqualTo("SCHEMA_CHANGE_SET_STATEMENT_INVALID");
        assertThat(garbage).bodyJson().extractingPath("$.statementIndex").asNumber().isEqualTo(0);
        assertThat(garbage).bodyJson().extractingPath("$.detail").asString().contains("Statement 1");

        var envelope = post(adminToken, createBody(pipeline.getId(), "envelope",
                "BEGIN; CREATE TABLE t (id INT); COMMIT;"));
        assertThat(envelope).hasStatus(422);
        assertThat(envelope).bodyJson().extractingPath("$.error").asString()
                .isEqualTo("SCHEMA_CHANGE_SET_STATEMENT_TRANSACTION_ENVELOPE");

        var multiple = post(adminToken, createBody(pipeline.getId(), "multiple",
                "CREATE TABLE t (id INT); DROP TABLE u"));
        assertThat(multiple).hasStatus(422);
        assertThat(multiple).bodyJson().extractingPath("$.error").asString()
                .isEqualTo("SCHEMA_CHANGE_SET_STATEMENT_MULTIPLE");

        var literal = post(adminToken, createBody(pipeline.getId(), "literal",
                "ALTER TABLE t ADD COLUMN c TEXT DEFAULT 'a;b';"));
        assertThat(literal).hasStatus(201);
        assertThat(changeSetRepository.findAll().stream().filter(s -> s.getOrganizationId().equals(org.getId())).count())
                .isEqualTo(1);
    }

    @Test
    void aBlockingRulesetRefusesTheSaveAndAWarnRidesOnTheWriteResponseOnly() {
        // The PRODUCTION datasource resolves this ruleset; unconfigured rules keep their defaults —
        // ddl_statement WARN, drop_statement BLOCK.
        var ruleset = mvc.post().uri("/api/v1/admin/sql-review-rulesets")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"name":"Production","environment":"PRODUCTION",
                         "rules":[{"rule_id":"select_star","severity":"OFF"}]}
                        """)
                .exchange();
        assertThat(ruleset).hasStatus(201);

        var blocked = post(adminToken, createBody(pipeline.getId(), "blocked",
                "CREATE TABLE t (id INT)", "DROP TABLE legacy_orders"));
        assertThat(blocked).hasStatus(422);
        assertThat(blocked).bodyJson().extractingPath("$.error").asString().isEqualTo("SCHEMA_CHANGE_SET_STATEMENT_BLOCKED");
        assertThat(blocked).bodyJson().extractingPath("$.findings[0].statement_index").asNumber().isEqualTo(1);
        assertThat(blocked).bodyJson().extractingPath("$.findings[0].rule_id").asString().isEqualTo("drop_statement");
        assertThat(blocked).bodyJson().extractingPath("$.findings[0].severity").asString().isEqualTo("BLOCK");
        assertThat(blocked).bodyJson().extractingPath("$.findings[0].datasource_id").asString()
                .isEqualTo(datasource.getId().toString());
        assertThat(blocked).bodyJson().extractingPath("$.findings[0].message").asString().isNotBlank();
        assertThat(changeSetRepository.findAll().stream().filter(s -> s.getOrganizationId().equals(org.getId())))
                .isEmpty();

        var warned = post(adminToken, createBody(pipeline.getId(), "warned", "CREATE TABLE t (id INT)"));
        assertThat(warned).hasStatus(201);
        assertThat(warned).bodyJson().extractingPath("$.review_warnings[0].rule_id").asString().isEqualTo("ddl_statement");
        assertThat(warned).bodyJson().extractingPath("$.review_warnings[0].severity").asString().isEqualTo("WARN");
        assertThat(warned).bodyJson().extractingPath("$.review_warnings[0].statement_index").asNumber().isEqualTo(0);
        assertThat(warned).bodyJson().extractingPath("$.review_warnings[0].message").asString().isNotBlank();

        var read = mvc.get().uri(BASE + "/" + idOf(warned))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken).exchange();
        assertThat(read).bodyJson().extractingPath("$.review_warnings.length()").asNumber().isEqualTo(0);
    }

    @Test
    void anUnboundPipelineAcceptsAnEmptySetButRefusesStatements() {
        var empty = post(adminToken, createBody(unboundPipeline.getId(), "empty"));
        assertThat(empty).hasStatus(201);
        assertThat(empty).bodyJson().doesNotHavePath("$.statements_checksum");

        var withStatements = post(adminToken, createBody(unboundPipeline.getId(), "with", "CREATE TABLE t (id INT)"));
        assertThat(withStatements).hasStatus(409);
        assertThat(withStatements).bodyJson().extractingPath("$.error").asString()
                .isEqualTo("SCHEMA_CHANGE_SET_NO_TARGET_DATASOURCE");
    }

    @Test
    void foreignPipelineDuplicateNameAndTheCapAreRefused() {
        var foreign = post(adminToken, createBody(foreignPipeline.getId(), "foreign"));
        assertThat(foreign).hasStatus(404);
        assertThat(foreign).bodyJson().extractingPath("$.error").asString().isEqualTo("SCHEMA_CHANGE_PIPELINE_NOT_FOUND");

        assertThat(post(adminToken, createBody(pipeline.getId(), "dup"))).hasStatus(201);
        var dup = post(adminToken, createBody(pipeline.getId(), "dup"));
        assertThat(dup).hasStatus(409);
        assertThat(dup).bodyJson().extractingPath("$.error").asString().isEqualTo("SCHEMA_CHANGE_SET_NAME_CONFLICT");

        var tooMany = post(adminToken, createBody(pipeline.getId(), "too-many",
                IntStream.range(0, 51).mapToObj(i -> "CREATE TABLE t" + i + " (id INT)").toArray(String[]::new)));
        assertThat(tooMany).hasStatus(400);
        assertThat(tooMany).bodyJson().extractingPath("$.error").asString().isEqualTo("SCHEMA_CHANGE_SET_STATEMENT_LIMIT");
        assertThat(tooMany).bodyJson().extractingPath("$.limit").asNumber().isEqualTo(50);
        assertThat(tooMany).bodyJson().extractingPath("$.actual").asNumber().isEqualTo(51);
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Test
    void replaceReordersAndRecomputesTheChecksumAndTheListFiltersByPipelineAndStatus() {
        var created = post(adminToken, createBody(pipeline.getId(), "reorder",
                "CREATE TABLE a (id INT)", "CREATE TABLE b (id INT)"));
        var id = idOf(created);
        var originalChecksum = body(created).replaceAll(".*\"statements_checksum\":\"([0-9a-f]{64})\".*", "$1");
        post(adminToken, createBody(unboundPipeline.getId(), "other-pipeline"));

        var replaced = mvc.put().uri(BASE + "/" + id + "/statements")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(statementsBody("CREATE TABLE b (id INT)", "CREATE TABLE a (id INT)"))
                .exchange();
        assertThat(replaced).hasStatus(200);
        assertThat(replaced).bodyJson().extractingPath("$.statements[0].sql_text").asString().isEqualTo("CREATE TABLE b (id INT)");
        assertThat(replaced).bodyJson().extractingPath("$.statements[1].sequence_order").asNumber().isEqualTo(1);
        assertThat(replaced).bodyJson().extractingPath("$.statements_checksum").asString().isNotEqualTo(originalChecksum);

        var filtered = mvc.get().uri(BASE + "?pipeline_id=" + pipeline.getId() + "&status=DRAFT&page=0&size=10")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken).exchange();
        assertThat(filtered).hasStatus(200);
        assertThat(filtered).bodyJson().extractingPath("$.total_elements").asNumber().isEqualTo(1);
        assertThat(filtered).bodyJson().extractingPath("$.content[0].id").asString().isEqualTo(id);
        assertThat(filtered).bodyJson().extractingPath("$.content[0].statements.length()").asNumber().isEqualTo(2);

        var archived = mvc.get().uri(BASE + "?status=ARCHIVED")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken).exchange();
        assertThat(archived).bodyJson().extractingPath("$.total_elements").asNumber().isEqualTo(0);
        var all = mvc.get().uri(BASE).header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken).exchange();
        assertThat(all).bodyJson().extractingPath("$.total_elements").asNumber().isEqualTo(2);
    }

    @Test
    void archivingFreezesStatementsAndOtherStatusTransitionsAreRefused() {
        var id = idOf(post(adminToken, createBody(pipeline.getId(), "archive", "CREATE TABLE a (id INT)")));

        var archived = put(adminToken, BASE + "/" + id, "{\"description\":\"retired\",\"status\":\"ARCHIVED\"}");
        assertThat(archived).hasStatus(200);
        assertThat(archived).bodyJson().extractingPath("$.status").asString().isEqualTo("ARCHIVED");
        assertThat(archived).bodyJson().extractingPath("$.description").asString().isEqualTo("retired");
        assertThat(archived).bodyJson().extractingPath("$.name").asString().isEqualTo("archive");

        var edit = put(adminToken, BASE + "/" + id + "/statements", statementsBody("CREATE TABLE b (id INT)"));
        assertThat(edit).hasStatus(409);
        assertThat(edit).bodyJson().extractingPath("$.error").asString().isEqualTo("SCHEMA_CHANGE_SET_ARCHIVED");

        var activate = put(adminToken, BASE + "/" + id, "{\"status\":\"ACTIVE\"}");
        assertThat(activate).hasStatus(409);
        assertThat(activate).bodyJson().extractingPath("$.error").asString()
                .isEqualTo("SCHEMA_CHANGE_SET_INVALID_STATUS_TRANSITION");
        assertThat(activate).bodyJson().extractingPath("$.currentStatus").asString().isEqualTo("ARCHIVED");
    }

    @Test
    void aPromotionFreezesStatementsAndDeleteUntilItFailsOrIsCancelled() {
        var id = UUID.fromString(idOf(post(adminToken, createBody(pipeline.getId(), "freeze", "CREATE TABLE a (id INT)"))));
        var promotion = savePromotion(id, SchemaChangePromotionStatus.PENDING);

        var edit = put(adminToken, BASE + "/" + id + "/statements", statementsBody("CREATE TABLE b (id INT)"));
        assertThat(edit).hasStatus(409);
        assertThat(edit).bodyJson().extractingPath("$.error").asString().isEqualTo("SCHEMA_CHANGE_SET_FROZEN");
        var delete = mvc.delete().uri(BASE + "/" + id).header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken).exchange();
        assertThat(delete).hasStatus(409);
        assertThat(delete).bodyJson().extractingPath("$.error").asString().isEqualTo("SCHEMA_CHANGE_SET_FROZEN");
        var rename = put(adminToken, BASE + "/" + id, "{\"name\":\"freeze-renamed\"}");
        assertThat(rename).hasStatus(200);

        promotion.setStatus(SchemaChangePromotionStatus.FAILED);
        promotionRepository.saveAndFlush(promotion);

        var thawed = put(adminToken, BASE + "/" + id + "/statements", statementsBody("CREATE TABLE b (id INT)"));
        assertThat(thawed).hasStatus(200);
        var deleted = mvc.delete().uri(BASE + "/" + id).header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken).exchange();
        assertThat(deleted).hasStatus(204);
        assertThat(changeSetRepository.findById(id)).isEmpty();
        assertThat(promotionRepository.findById(promotion.getId())).isEmpty();
    }

    // ── Access ────────────────────────────────────────────────────────────────

    @Test
    void crossOrgIdsRead404AndTheAnalystIsRefused() {
        var id = idOf(post(adminToken, createBody(pipeline.getId(), "scoped")));
        var otherAdmin = saveUser(otherOrg, "other-admin-sc-" + suffix, UserRoleType.ADMIN);
        try {
            var foreignRead = mvc.get().uri(BASE + "/" + id)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + generateToken(otherAdmin)).exchange();
            assertThat(foreignRead).hasStatus(404);
            assertThat(foreignRead).bodyJson().extractingPath("$.error").asString().isEqualTo("SCHEMA_CHANGE_SET_NOT_FOUND");
        } finally {
            userRepository.deleteById(otherAdmin.getId());
        }

        assertThat(mvc.get().uri(BASE).header(HttpHeaders.AUTHORIZATION, "Bearer " + analystToken).exchange())
                .hasStatus(403);
        assertThat(post(analystToken, createBody(pipeline.getId(), "analyst"))).hasStatus(403);
        assertThat(mvc.get().uri(BASE).exchange()).hasStatus(401);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private MvcTestResult post(String token, String body) {
        return mvc.post().uri(BASE)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .exchange();
    }

    private MvcTestResult put(String token, String uri, String body) {
        return mvc.put().uri(uri)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .exchange();
    }

    private static String body(MvcTestResult result) {
        try {
            return result.getResponse().getContentAsString();
        } catch (java.io.UnsupportedEncodingException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static String idOf(MvcTestResult result) {
        return body(result).replaceAll(".*?\"id\":\"([0-9a-f-]{36})\".*", "$1");
    }

    private static String statements(String... sql) {
        return java.util.Arrays.stream(sql)
                .map(s -> "{\"sql_text\":" + quote(s) + "}")
                .collect(Collectors.joining(",", "[", "]"));
    }

    private static String createBody(UUID pipelineId, String name, String... sql) {
        return "{\"pipeline_id\":\"" + pipelineId + "\",\"name\":\"" + name + "\",\"statements\":" + statements(sql) + "}";
    }

    private static String statementsBody(String... sql) {
        return "{\"statements\":" + statements(sql) + "}";
    }

    private static String quote(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }

    private OrganizationEntity saveOrg(String name, String slug) {
        var entity = new OrganizationEntity();
        entity.setId(UUID.randomUUID());
        entity.setName(name);
        entity.setSlug(slug);
        return organizationRepository.save(entity);
    }

    private UserEntity saveUser(OrganizationEntity organization, String prefix, UserRoleType role) {
        var entity = new UserEntity();
        entity.setId(UUID.randomUUID());
        entity.setEmail(prefix + "@example.com");
        entity.setDisplayName(prefix);
        entity.setPasswordHash("x");
        entity.setRole(role);
        entity.setAuthProvider(AuthProviderType.LOCAL);
        entity.setActive(true);
        entity.setOrganization(organization);
        return userRepository.save(entity);
    }

    private DatasourceEntity saveDatasource(OrganizationEntity organization) {
        var ds = new DatasourceEntity();
        ds.setId(UUID.randomUUID());
        ds.setOrganization(organization);
        ds.setName("prod-" + suffix);
        ds.setDbType(DbType.POSTGRESQL);
        ds.setHost("nope.invalid");
        ds.setPort(65000);
        ds.setDatabaseName("appdb");
        ds.setUsername("svc");
        ds.setPasswordEncrypted(encryptionService.encrypt("seed-password"));
        ds.setSslMode(SslMode.DISABLE);
        ds.setConnectionPoolSize(10);
        ds.setMaxRowsPerQuery(1000);
        ds.setRequireReviewReads(false);
        ds.setRequireReviewWrites(true);
        ds.setAiAnalysisEnabled(false);
        ds.setActive(true);
        ds.setEnvironment(DatasourceEnvironment.PRODUCTION);
        return datasourceRepository.save(ds);
    }

    private DeploymentPipelineEntity savePipeline(OrganizationEntity organization, String name) {
        var entity = new DeploymentPipelineEntity();
        entity.setId(UUID.randomUUID());
        entity.setOrganizationId(organization.getId());
        entity.setName(name);
        entity.setProvider(PipelineProvider.GITHUB_ACTIONS);
        entity.setActive(true);
        entity.setAiAnalysisEnabled(false);
        return pipelineRepository.save(entity);
    }

    private void saveEnvironment(DeploymentPipelineEntity owner, String name, int sortOrder, UUID datasourceId) {
        var entity = new DeploymentEnvironmentEntity();
        entity.setId(UUID.randomUUID());
        entity.setPipelineId(owner.getId());
        entity.setName(name);
        entity.setSortOrder(sortOrder);
        entity.setRequireReview(true);
        entity.setAllowBreakGlass(false);
        entity.setDatasourceId(datasourceId);
        environmentRepository.save(entity);
    }

    private SchemaChangeSetPromotionEntity savePromotion(UUID changeSetId, SchemaChangePromotionStatus status) {
        var entity = new SchemaChangeSetPromotionEntity();
        entity.setId(UUID.randomUUID());
        entity.setOrganizationId(org.getId());
        entity.setChangeSet(changeSetRepository.getReferenceById(changeSetId));
        entity.setEnvironmentId(UUID.randomUUID());
        entity.setDatasourceId(datasource.getId());
        entity.setStatus(status);
        entity.setStatementsChecksum("a".repeat(64));
        entity.setPromotedBy(admin.getId());
        return promotionRepository.saveAndFlush(entity);
    }

    private String generateToken(UserEntity entity) {
        var view = new UserView(entity.getId(), entity.getEmail(), entity.getDisplayName(),
                entity.getRole(), entity.getRoleRef() == null ? null : entity.getRoleRef().getId(),
                entity.roleName(), entity.getOrganization().getId(), entity.isActive(),
                entity.getAuthProvider(), entity.getPasswordHash(), entity.getLastLoginAt(),
                entity.getPreferredLanguage(), entity.isTotpEnabled(), entity.isPlatformAdmin(),
                entity.getCreatedAt());
        return jwtService.generateAccessToken(view);
    }
}
