package com.bablsoft.accessflow.security.internal.web;

import com.bablsoft.accessflow.TestcontainersConfig;
import com.bablsoft.accessflow.core.api.AuthProviderType;
import com.bablsoft.accessflow.core.api.DbType;
import com.bablsoft.accessflow.core.api.PageRequest;
import com.bablsoft.accessflow.core.api.PageResponse;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.core.internal.persistence.entity.OrganizationEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.UserEntity;
import com.bablsoft.accessflow.core.internal.persistence.repo.DatasourceRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.OrganizationRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.QueryRequestRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.UserRepository;
import com.bablsoft.accessflow.proxy.api.DatasourceHealthService;
import com.bablsoft.accessflow.proxy.api.DatasourceHealthSnapshot;
import com.bablsoft.accessflow.security.internal.jwt.JwtService;
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
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

@SpringBootTest
@ImportTestcontainers(TestcontainersConfig.class)
class AdminDatasourceHealthControllerIntegrationTest {

    @Autowired WebApplicationContext context;
    @Autowired UserRepository userRepository;
    @Autowired OrganizationRepository organizationRepository;
    @Autowired DatasourceRepository datasourceRepository;
    @Autowired QueryRequestRepository queryRequestRepository;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired JwtService jwtService;
    @MockitoBean DatasourceHealthService datasourceHealthService;

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
        queryRequestRepository.deleteAll();
        datasourceRepository.deleteAll();
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

    @Test
    void returnsSnapshotPageForAdmin() {
        var dsId = UUID.randomUUID();
        var snapshot = new DatasourceHealthSnapshot(dsId, "prod-db", DbType.POSTGRESQL, true,
                2, 8, 0, 10, 20, 42L, 12.5, 88.0, 3L);
        when(datasourceHealthService.snapshot(eq(org.getId()), any()))
                .thenReturn(new PageResponse<>(List.of(snapshot), 0, 50, 1, 1));

        var result = mvc.get().uri("/api/v1/admin/datasource-health")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .exchange();

        assertThat(result).hasStatus(200);
        assertThat(result).bodyJson().extractingPath("$.total_elements").asNumber().isEqualTo(1);
        assertThat(result).bodyJson().extractingPath("$.content[0].datasource_id").asString()
                .isEqualTo(dsId.toString());
        assertThat(result).bodyJson().extractingPath("$.content[0].datasource_name").asString()
                .isEqualTo("prod-db");
        assertThat(result).bodyJson().extractingPath("$.content[0].db_type").asString()
                .isEqualTo("POSTGRESQL");
        assertThat(result).bodyJson().extractingPath("$.content[0].pool_active").asNumber()
                .isEqualTo(2);
        assertThat(result).bodyJson().extractingPath("$.content[0].queries_last_24h").asNumber()
                .isEqualTo(42);
        assertThat(result).bodyJson().extractingPath("$.content[0].errors_last_24h").asNumber()
                .isEqualTo(3);
        assertThat(result).bodyJson().extractingPath("$.content[0].execution_ms_p50").asNumber()
                .isEqualTo(12.5);
    }

    @Test
    void rejectsNonAdminWith403() {
        var result = mvc.get().uri("/api/v1/admin/datasource-health")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + analystToken)
                .exchange();

        assertThat(result).hasStatus(403);
    }

    @Test
    void requiresAuthentication() {
        var result = mvc.get().uri("/api/v1/admin/datasource-health").exchange();

        assertThat(result).hasStatus(401);
    }

    @Test
    void clampsOversizedPageRequestToHundred() {
        when(datasourceHealthService.snapshot(any(), any())).thenReturn(PageResponse.empty(0, 100));

        mvc.get().uri("/api/v1/admin/datasource-health?size=500")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .exchange();

        var captor = ArgumentCaptor.forClass(PageRequest.class);
        verify(datasourceHealthService).snapshot(eq(org.getId()), captor.capture());
        assertThat(captor.getValue().size()).isEqualTo(100);
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
        var view = new com.bablsoft.accessflow.core.api.UserView(
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
