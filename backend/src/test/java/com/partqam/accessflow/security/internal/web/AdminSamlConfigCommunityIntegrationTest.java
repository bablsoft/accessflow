package com.partqam.accessflow.security.internal.web;

import com.partqam.accessflow.TestcontainersConfig;
import com.partqam.accessflow.core.api.AuthProviderType;
import com.partqam.accessflow.core.api.EditionType;
import com.partqam.accessflow.core.api.UserRoleType;
import com.partqam.accessflow.core.api.UserView;
import com.partqam.accessflow.core.internal.persistence.entity.OrganizationEntity;
import com.partqam.accessflow.core.internal.persistence.entity.UserEntity;
import com.partqam.accessflow.core.internal.persistence.repo.OrganizationRepository;
import com.partqam.accessflow.core.internal.persistence.repo.UserRepository;
import com.partqam.accessflow.security.internal.jwt.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.web.context.WebApplicationContext;

import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateCrtKey;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

/**
 * Confirms SAML admin routes are absent on Community builds — the conditional bean is not
 * registered, so the dispatcher returns 404.
 */
@SpringBootTest
@ImportTestcontainers(TestcontainersConfig.class)
@TestPropertySource(properties = "accessflow.edition=community")
class AdminSamlConfigCommunityIntegrationTest {

    @Autowired WebApplicationContext context;
    @Autowired UserRepository userRepository;
    @Autowired OrganizationRepository organizationRepository;
    @Autowired JwtService jwtService;

    private MockMvcTester mvc;
    private OrganizationEntity org;
    private String adminToken;

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
        userRepository.deleteAll();
        organizationRepository.deleteAll();

        org = new OrganizationEntity();
        org.setId(UUID.randomUUID());
        org.setName("Primary");
        org.setSlug("primary-" + UUID.randomUUID());
        org.setEdition(EditionType.COMMUNITY);
        organizationRepository.save(org);

        var admin = new UserEntity();
        admin.setId(UUID.randomUUID());
        admin.setEmail("admin@example.com");
        admin.setDisplayName("ADMIN");
        admin.setPasswordHash("hashed");
        admin.setRole(UserRoleType.ADMIN);
        admin.setAuthProvider(AuthProviderType.LOCAL);
        admin.setActive(true);
        admin.setOrganization(org);
        admin = userRepository.save(admin);
        var view = new UserView(
                admin.getId(),
                admin.getEmail(),
                admin.getDisplayName(),
                admin.getRole(),
                admin.getOrganization().getId(),
                admin.isActive(),
                admin.getAuthProvider(),
                admin.getPasswordHash(),
                admin.getLastLoginAt(),
                admin.getPreferredLanguage(),
                admin.isTotpEnabled(),
                admin.getCreatedAt());
        adminToken = jwtService.generateAccessToken(view);
    }

    @Test
    void communityBuildHidesSamlRoute() {
        var get = mvc.get().uri("/api/v1/admin/saml-config")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .exchange();
        assertThat(get).hasStatus(404);
    }
}
