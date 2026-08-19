package com.bablsoft.accessflow.compliance.internal.web;

import com.bablsoft.accessflow.TestcontainersConfig;
import com.bablsoft.accessflow.TestSystemRoleSeeder;
import com.bablsoft.accessflow.compliance.api.ComplianceReportFormat;
import com.bablsoft.accessflow.compliance.api.ExportDecision;
import com.bablsoft.accessflow.compliance.api.ResultExportDeniedException;
import com.bablsoft.accessflow.compliance.api.ResultExportNotFoundException;
import com.bablsoft.accessflow.compliance.api.ResultExportService;
import com.bablsoft.accessflow.compliance.api.ResultExportUnavailableException;
import com.bablsoft.accessflow.compliance.api.SignedExport;
import com.bablsoft.accessflow.core.api.AuthProviderType;
import com.bablsoft.accessflow.core.api.DataClassification;
import com.bablsoft.accessflow.core.api.ExportPolicyMode;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.context.MessageSource;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.web.context.WebApplicationContext;

import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateCrtKey;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

/**
 * Web-layer IT for {@code ResultExportController} (#626), modeled on
 * {@link ComplianceReportControllerIntegrationTest}'s context setup. {@code ResultExportService}
 * is mocked so the test pins the HTTP contract (headers, ProblemDetail shapes, snake_case
 * decision body). {@code MessageSource} is mocked (returns the code, plus joined args) because
 * the #626 i18n keys land in a separate commit — noted for the follow-up.
 */
@SpringBootTest
@ImportTestcontainers(TestcontainersConfig.class)
class ResultExportControllerIntegrationTest {

    @Autowired WebApplicationContext context;
    @Autowired OrganizationRepository organizationRepository;
    @Autowired UserRepository userRepository;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired JwtService jwtService;

    @MockitoBean ResultExportService resultExportService;
    @MockitoBean MessageSource messageSource;

    private MockMvcTester mvc;
    private OrganizationEntity org;
    private UserEntity analyst;
    private String analystToken;
    private final UUID queryId = UUID.randomUUID();

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
        cleanup();
        // The #626 message keys are not in messages.properties yet — resolve any code to itself
        // (with args appended) so the ProblemDetail handlers stay exercisable.
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

        analyst = saveUser("analyst-rex@example.com", UserRoleType.ANALYST);
        analystToken = generateToken(analyst);
    }

    @AfterEach
    void cleanup() {
        jdbcTemplate.execute("TRUNCATE TABLE organizations CASCADE");
        TestSystemRoleSeeder.reseedSystemRoles(jdbcTemplate);
    }

    private String base() {
        return "/api/v1/queries/" + queryId + "/results";
    }

    private SignedExport signedCsv(boolean truncated) {
        return new SignedExport("id,name\r\n1,a\r\n".getBytes(StandardCharsets.UTF_8),
                "query-results-abcd1234-20260702T090000Z.csv", "text/csv; charset=utf-8",
                "ab".repeat(32), "c2lnbmF0dXJl", "SHA256withRSA", truncated);
    }

    @Test
    void exportWithoutTokenReturns401() {
        var result = mvc.get().uri(base() + "/export").exchange();

        assertThat(result).hasStatus(401);
    }

    @Test
    void exportStreamsSignedContentWithHeaders() throws Exception {
        when(resultExportService.export(any(), any(), any(), any(), any(), anyBoolean(), any(),
                any())).thenReturn(signedCsv(false));

        var result = mvc.get().uri(base() + "/export")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + analystToken)
                .exchange();

        assertThat(result).hasStatus(200);
        assertThat(result.getResponse().getContentType()).startsWith("text/csv");
        assertThat(result.getResponse().getHeader(HttpHeaders.CONTENT_DISPOSITION))
                .isEqualTo("attachment; filename=\"query-results-abcd1234-20260702T090000Z.csv\"");
        assertThat(result.getResponse().getHeader("X-AccessFlow-Signature"))
                .isEqualTo("c2lnbmF0dXJl");
        assertThat(result.getResponse().getHeader("X-AccessFlow-Signature-Algorithm"))
                .isEqualTo("SHA256withRSA");
        assertThat(result.getResponse().getHeader("X-AccessFlow-Content-SHA256"))
                .isEqualTo("ab".repeat(32));
        assertThat(result.getResponse().getHeader("X-AccessFlow-Export-Truncated")).isNull();
        assertThat(result.getResponse().getContentAsString()).isEqualTo("id,name\r\n1,a\r\n");
    }

    @Test
    void truncatedExportSetsTruncatedHeader() {
        when(resultExportService.export(any(), any(), any(), any(), any(), anyBoolean(), any(),
                any())).thenReturn(signedCsv(true));

        var result = mvc.get().uri(base() + "/export")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + analystToken)
                .exchange();

        assertThat(result).hasStatus(200);
        assertThat(result.getResponse().getHeader("X-AccessFlow-Export-Truncated"))
                .isEqualTo("true");
    }

    @Test
    void formatDefaultsToCsvAndPassesCallerIdentity() {
        when(resultExportService.export(any(), any(), any(), any(), any(), anyBoolean(), any(),
                any())).thenReturn(signedCsv(false));

        mvc.get().uri(base() + "/export")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + analystToken)
                .exchange();

        verify(resultExportService).export(eq(org.getId()), eq(queryId),
                eq(ComplianceReportFormat.CSV), eq(analyst.getId()),
                eq("analyst-rex@example.com"), eq(false), any(), any());
    }

    @Test
    void pdfFormatParamIsPassedThrough() {
        when(resultExportService.export(any(), any(), any(), any(), any(), anyBoolean(), any(),
                any())).thenReturn(new SignedExport("%PDF-".getBytes(StandardCharsets.UTF_8),
                "query-results-abcd1234-20260702T090000Z.pdf", "application/pdf",
                "cd".repeat(32), "sig", "SHA256withRSA", false));

        var result = mvc.get().uri(base() + "/export?format=PDF")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + analystToken)
                .exchange();

        assertThat(result).hasStatus(200);
        assertThat(result.getResponse().getContentType()).isEqualTo("application/pdf");
        verify(resultExportService).export(eq(org.getId()), eq(queryId),
                eq(ComplianceReportFormat.PDF), eq(analyst.getId()),
                eq("analyst-rex@example.com"), eq(false), any(), any());
    }

    @Test
    void classifiedDenyReturns403WithClassificationsInDetail() {
        when(resultExportService.export(any(), any(), any(), any(), any(), anyBoolean(), any(),
                any())).thenThrow(new ResultExportDeniedException(
                List.of(DataClassification.PII, DataClassification.PCI)));

        var result = mvc.get().uri(base() + "/export")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + analystToken)
                .exchange();

        assertThat(result).hasStatus(403);
        assertThat(result).bodyJson().extractingPath("$.error").asString()
                .isEqualTo("RESULT_EXPORT_DENIED");
        assertThat(result).bodyJson().extractingPath("$.detail").asString()
                .contains("PII, PCI");
    }

    @Test
    void blanketDenyReturns403WithUnclassifiedDetail() {
        when(resultExportService.export(any(), any(), any(), any(), any(), anyBoolean(), any(),
                any())).thenThrow(new ResultExportDeniedException(List.of()));

        var result = mvc.get().uri(base() + "/export")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + analystToken)
                .exchange();

        assertThat(result).hasStatus(403);
        assertThat(result).bodyJson().extractingPath("$.error").asString()
                .isEqualTo("RESULT_EXPORT_DENIED");
        assertThat(result).bodyJson().extractingPath("$.detail").asString()
                .isEqualTo("error.result_export_denied");
    }

    @Test
    void unknownQueryReturns404ProblemDetail() {
        when(resultExportService.export(any(), any(), any(), any(), any(), anyBoolean(), any(),
                any())).thenThrow(new ResultExportNotFoundException(queryId));

        var result = mvc.get().uri(base() + "/export")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + analystToken)
                .exchange();

        assertThat(result).hasStatus(404);
        assertThat(result).bodyJson().extractingPath("$.error").asString()
                .isEqualTo("QUERY_REQUEST_NOT_FOUND");
    }

    @Test
    void nonSelectReturns422ProblemDetail() {
        when(resultExportService.export(any(), any(), any(), any(), any(), anyBoolean(), any(),
                any())).thenThrow(new ResultExportUnavailableException(queryId));

        var result = mvc.get().uri(base() + "/export")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + analystToken)
                .exchange();

        assertThat(result).hasStatus(422);
        assertThat(result).bodyJson().extractingPath("$.error").asString()
                .isEqualTo("RESULTS_NOT_AVAILABLE");
    }

    @Test
    void exportDecisionReturnsSnakeCaseBody() {
        var policyId = UUID.randomUUID();
        when(resultExportService.decisionFor(org.getId(), queryId, analyst.getId(), false))
                .thenReturn(new ExportDecision(true, ExportPolicyMode.ROW_CAP, 100, true,
                        List.of(policyId), List.of(DataClassification.PII)));

        var result = mvc.get().uri(base() + "/export-decision")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + analystToken)
                .exchange();

        assertThat(result).hasStatus(200);
        assertThat(result).bodyJson().extractingPath("$.allowed").asBoolean().isTrue();
        assertThat(result).bodyJson().extractingPath("$.effective_mode").asString()
                .isEqualTo("ROW_CAP");
        assertThat(result).bodyJson().extractingPath("$.row_cap").asNumber().isEqualTo(100);
        assertThat(result).bodyJson().extractingPath("$.watermark").asBoolean().isTrue();
        assertThat(result).bodyJson().extractingPath("$.policy_ids").asArray()
                .containsExactly(policyId.toString());
        assertThat(result).bodyJson().extractingPath("$.classifications_present").asArray()
                .containsExactly("PII");
    }

    @Test
    void exportDecisionReturns404WhenNotVisible() {
        when(resultExportService.decisionFor(org.getId(), queryId, analyst.getId(), false))
                .thenThrow(new ResultExportNotFoundException(queryId));

        var result = mvc.get().uri(base() + "/export-decision")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + analystToken)
                .exchange();

        assertThat(result).hasStatus(404);
        assertThat(result).bodyJson().extractingPath("$.error").asString()
                .isEqualTo("QUERY_REQUEST_NOT_FOUND");
    }

    private UserEntity saveUser(String email, UserRoleType role) {
        var user = new UserEntity();
        user.setId(UUID.randomUUID());
        user.setEmail(email);
        user.setDisplayName(email);
        user.setPasswordHash("hashed");
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
