package com.bablsoft.accessflow.security.internal.web;

import com.bablsoft.accessflow.TestcontainersConfig;
import com.bablsoft.accessflow.TestSystemRoleSeeder;
import com.bablsoft.accessflow.audit.api.AuditAction;
import com.bablsoft.accessflow.audit.api.AuditEntry;
import com.bablsoft.accessflow.audit.api.AuditLogService;
import com.bablsoft.accessflow.core.api.AuthProviderType;
import com.bablsoft.accessflow.core.api.CreateExportPolicyCommand;
import com.bablsoft.accessflow.core.api.DataClassification;
import com.bablsoft.accessflow.core.api.DatasourceNotFoundException;
import com.bablsoft.accessflow.core.api.ExportPolicyAdminService;
import com.bablsoft.accessflow.core.api.ExportPolicyMode;
import com.bablsoft.accessflow.core.api.ExportPolicyNotFoundException;
import com.bablsoft.accessflow.core.api.ExportPolicyView;
import com.bablsoft.accessflow.core.api.IllegalExportPolicyException;
import com.bablsoft.accessflow.core.api.UpdateExportPolicyCommand;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.core.api.UserView;
import com.bablsoft.accessflow.core.internal.persistence.entity.OrganizationEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.UserEntity;
import com.bablsoft.accessflow.core.internal.persistence.repo.OrganizationRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.UserRepository;
import com.bablsoft.accessflow.security.internal.jwt.JwtService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.context.MessageSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.web.context.WebApplicationContext;

import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateCrtKey;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

/**
 * Web-layer IT for {@code ExportPolicyController} (#626), modeled on
 * {@link RowSecurityPolicyControllerIntegrationTest}. The admin service and audit service are
 * mocked so the test pins the HTTP contract (status codes, ProblemDetail shape, snake_case
 * response mapping) rather than persistence. {@code MessageSource} is mocked (returns the code)
 * because the #626 i18n keys land in a separate commit — noted for the follow-up.
 */
@SpringBootTest
@ImportTestcontainers(TestcontainersConfig.class)
class ExportPolicyControllerIntegrationTest {

    @Autowired WebApplicationContext context;
    @Autowired UserRepository userRepository;
    @Autowired OrganizationRepository organizationRepository;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired JwtService jwtService;
    @Autowired JdbcTemplate jdbcTemplate;

    @MockitoBean ExportPolicyAdminService exportPolicyAdminService;
    @MockitoBean AuditLogService auditLogService;
    @MockitoBean MessageSource messageSource;

    private MockMvcTester mvc;
    private OrganizationEntity org;
    private UUID datasourceId;
    private String adminToken;
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
        cleanup();
        // The #626 message keys are not in messages.properties yet — resolve any code to itself.
        when(messageSource.getMessage(anyString(), any(), any(Locale.class)))
                .thenAnswer(inv -> {
                    Object[] args = inv.getArgument(1);
                    String code = inv.getArgument(0);
                    return args == null || args.length == 0 ? code
                            : code + " " + Arrays.stream(args).map(String::valueOf)
                                    .collect(Collectors.joining(", "));
                });

        org = new OrganizationEntity();
        org.setId(UUID.randomUUID());
        org.setName("Org");
        org.setSlug("org-" + UUID.randomUUID());
        organizationRepository.save(org);

        adminToken = generateToken(saveUser("admin-ep@example.com", UserRoleType.ADMIN));
        analystToken = generateToken(saveUser("analyst-ep@example.com", UserRoleType.ANALYST));
        datasourceId = UUID.randomUUID();
    }

    @AfterEach
    void cleanup() {
        jdbcTemplate.execute("TRUNCATE TABLE organizations CASCADE");
        TestSystemRoleSeeder.reseedSystemRoles(jdbcTemplate);
    }

    private String base() {
        return "/api/v1/datasources/" + datasourceId + "/export-policies";
    }

    private ExportPolicyView view(UUID id, ExportPolicyMode mode, Integer rowCap) {
        return new ExportPolicyView(id, datasourceId, mode, rowCap,
                List.of(DataClassification.PII), List.of("ANALYST"), List.of(), List.of(),
                true, Instant.parse("2026-07-01T10:00:00Z"), Instant.parse("2026-07-01T10:00:00Z"));
    }

    @Test
    void listWithoutTokenReturns401() {
        var result = mvc.get().uri(base()).exchange();

        assertThat(result).hasStatus(401);
    }

    @Test
    void listByAnalystReturns403() {
        var result = mvc.get().uri(base())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + analystToken)
                .exchange();

        assertThat(result).hasStatus(403);
    }

    @Test
    void listReturns200WithSnakeCasePolicies() {
        var id = UUID.randomUUID();
        when(exportPolicyAdminService.listForDatasource(datasourceId, org.getId()))
                .thenReturn(List.of(view(id, ExportPolicyMode.ROW_CAP, 100)));

        var result = mvc.get().uri(base())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .exchange();

        assertThat(result).hasStatus(200);
        assertThat(result).bodyJson().extractingPath("$.content[0].id").asString()
                .isEqualTo(id.toString());
        assertThat(result).bodyJson().extractingPath("$.content[0].mode").asString()
                .isEqualTo("ROW_CAP");
        assertThat(result).bodyJson().extractingPath("$.content[0].row_cap").asNumber()
                .isEqualTo(100);
        assertThat(result).bodyJson().extractingPath("$.content[0].deny_classifications").asArray()
                .containsExactly("PII");
        assertThat(result).bodyJson().extractingPath("$.content[0].applies_to_roles").asArray()
                .containsExactly("ANALYST");
        assertThat(result).bodyJson().extractingPath("$.content[0].enabled").asBoolean().isTrue();
        assertThat(result).bodyJson().extractingPath("$.content[0].datasource_id").asString()
                .isEqualTo(datasourceId.toString());
    }

    @Test
    void createReturns201WithLocationAndAudits() {
        var id = UUID.randomUUID();
        when(exportPolicyAdminService.create(eq(datasourceId), eq(org.getId()),
                any(CreateExportPolicyCommand.class)))
                .thenReturn(view(id, ExportPolicyMode.ROW_CAP, 100));

        var result = mvc.post().uri(base())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"mode":"ROW_CAP","row_cap":100,
                         "deny_classifications":["PII"],
                         "applies_to_roles":["ANALYST"],"enabled":true}
                        """)
                .exchange();

        assertThat(result).hasStatus(201);
        assertThat(result.getResponse().getHeader(HttpHeaders.LOCATION))
                .contains("/export-policies/" + id);
        assertThat(result).bodyJson().extractingPath("$.mode").asString().isEqualTo("ROW_CAP");
        assertThat(result).bodyJson().extractingPath("$.row_cap").asNumber().isEqualTo(100);

        var commandCaptor = ArgumentCaptor.forClass(CreateExportPolicyCommand.class);
        verify(exportPolicyAdminService).create(eq(datasourceId), eq(org.getId()),
                commandCaptor.capture());
        assertThat(commandCaptor.getValue().mode()).isEqualTo(ExportPolicyMode.ROW_CAP);
        assertThat(commandCaptor.getValue().rowCap()).isEqualTo(100);
        assertThat(commandCaptor.getValue().denyClassifications())
                .containsExactly(DataClassification.PII);

        var auditCaptor = ArgumentCaptor.forClass(AuditEntry.class);
        verify(auditLogService).record(auditCaptor.capture());
        assertThat(auditCaptor.getValue().action()).isEqualTo(AuditAction.EXPORT_POLICY_CREATED);
        assertThat(auditCaptor.getValue().resourceId()).isEqualTo(id);
        assertThat(auditCaptor.getValue().metadata())
                .containsEntry("mode", "ROW_CAP")
                .containsEntry("row_cap", 100)
                .containsEntry("datasource_id", datasourceId.toString());
    }

    @Test
    void updateReturns200() {
        var id = UUID.randomUUID();
        when(exportPolicyAdminService.update(eq(id), eq(datasourceId), eq(org.getId()),
                any(UpdateExportPolicyCommand.class)))
                .thenReturn(view(id, ExportPolicyMode.WATERMARK, null));

        var result = mvc.put().uri(base() + "/" + id)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"mode\":\"WATERMARK\",\"enabled\":true}")
                .exchange();

        assertThat(result).hasStatus(200);
        assertThat(result).bodyJson().extractingPath("$.mode").asString().isEqualTo("WATERMARK");

        var auditCaptor = ArgumentCaptor.forClass(AuditEntry.class);
        verify(auditLogService).record(auditCaptor.capture());
        assertThat(auditCaptor.getValue().action()).isEqualTo(AuditAction.EXPORT_POLICY_UPDATED);
    }

    @Test
    void deleteReturns204AndAudits() {
        var id = UUID.randomUUID();

        var result = mvc.delete().uri(base() + "/" + id)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .exchange();

        assertThat(result).hasStatus(204);
        verify(exportPolicyAdminService).delete(id, datasourceId, org.getId());
        var auditCaptor = ArgumentCaptor.forClass(AuditEntry.class);
        verify(auditLogService).record(auditCaptor.capture());
        assertThat(auditCaptor.getValue().action()).isEqualTo(AuditAction.EXPORT_POLICY_DELETED);
        assertThat(auditCaptor.getValue().resourceId()).isEqualTo(id);
    }

    @Test
    void createWithMissingModeReturns400() {
        var result = mvc.post().uri(base())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"row_cap\":100}")
                .exchange();

        assertThat(result).hasStatus(400);
        assertThat(result).bodyJson().extractingPath("$.error").asString()
                .isEqualTo("VALIDATION_ERROR");
    }

    @Test
    void illegalExportPolicyReturns422() {
        when(exportPolicyAdminService.create(eq(datasourceId), eq(org.getId()),
                any(CreateExportPolicyCommand.class)))
                .thenThrow(new IllegalExportPolicyException("row cap not allowed for ALLOW"));

        var result = mvc.post().uri(base())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"mode\":\"ALLOW\",\"row_cap\":5}")
                .exchange();

        assertThat(result).hasStatus(422);
        assertThat(result).bodyJson().extractingPath("$.error").asString()
                .isEqualTo("ILLEGAL_EXPORT_POLICY");
        assertThat(result).bodyJson().extractingPath("$.detail").asString()
                .isEqualTo("row cap not allowed for ALLOW");
    }

    @Test
    void updateUnknownPolicyReturns404() {
        var id = UUID.randomUUID();
        when(exportPolicyAdminService.update(eq(id), eq(datasourceId), eq(org.getId()),
                any(UpdateExportPolicyCommand.class)))
                .thenThrow(new ExportPolicyNotFoundException(id));

        var result = mvc.put().uri(base() + "/" + id)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"mode\":\"ALLOW\"}")
                .exchange();

        assertThat(result).hasStatus(404);
        assertThat(result).bodyJson().extractingPath("$.error").asString()
                .isEqualTo("EXPORT_POLICY_NOT_FOUND");
    }

    @Test
    void listUnknownDatasourceReturns404() {
        when(exportPolicyAdminService.listForDatasource(datasourceId, org.getId()))
                .thenThrow(new DatasourceNotFoundException(datasourceId));

        var result = mvc.get().uri(base())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .exchange();

        assertThat(result).hasStatus(404);
    }

    private UserEntity saveUser(String email, UserRoleType role) {
        var user = new UserEntity();
        user.setId(UUID.randomUUID());
        user.setEmail(email);
        user.setDisplayName(email);
        user.setPasswordHash(passwordEncoder.encode("Password123!"));
        user.setRole(role);
        user.setAuthProvider(AuthProviderType.LOCAL);
        user.setActive(true);
        user.setOrganization(org);
        return userRepository.save(user);
    }

    private String generateToken(UserEntity entity) {
        var view = new UserView(entity.getId(), entity.getEmail(), entity.getDisplayName(),
                entity.getRole(), entity.getOrganization().getId(), entity.isActive(),
                entity.getAuthProvider(), entity.getPasswordHash(), entity.getLastLoginAt(),
                entity.getPreferredLanguage(), entity.isTotpEnabled(), entity.getCreatedAt());
        return jwtService.generateAccessToken(view);
    }
}
