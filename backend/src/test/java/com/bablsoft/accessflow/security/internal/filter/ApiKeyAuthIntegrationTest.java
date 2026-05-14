package com.bablsoft.accessflow.security.internal.filter;

import com.bablsoft.accessflow.TestcontainersConfig;
import com.bablsoft.accessflow.core.api.AuthProviderType;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.core.internal.persistence.entity.OrganizationEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.UserEntity;
import com.bablsoft.accessflow.core.internal.persistence.repo.DatasourceRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.OrganizationRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.UserRepository;
import com.bablsoft.accessflow.security.api.ApiKeyService;
import com.bablsoft.accessflow.security.internal.persistence.repo.ApiKeyRepository;
import com.bablsoft.accessflow.security.internal.persistence.repo.OAuth2ConfigRepository;
import com.bablsoft.accessflow.security.internal.persistence.repo.SamlConfigRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.web.context.WebApplicationContext;

import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateCrtKey;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

@SpringBootTest(properties = "accessflow.encryption-key=00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff")
@ImportTestcontainers(TestcontainersConfig.class)
class ApiKeyAuthIntegrationTest {

    @Autowired WebApplicationContext context;
    @Autowired UserRepository userRepository;
    @Autowired OrganizationRepository organizationRepository;
    @Autowired ApiKeyRepository apiKeyRepository;
    @Autowired DatasourceRepository datasourceRepository;
    @Autowired OAuth2ConfigRepository oauth2ConfigRepository;
    @Autowired SamlConfigRepository samlConfigRepository;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired ApiKeyService apiKeyService;

    private MockMvcTester mvc;
    private UserEntity user;
    private String rawKey;

    @DynamicPropertySource
    static void rsaProperties(DynamicPropertyRegistry registry) throws Exception {
        var kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        var pk = (RSAPrivateCrtKey) kpg.generateKeyPair().getPrivate();
        var pem = "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(pk.getEncoded())
                + "\n-----END PRIVATE KEY-----";
        registry.add("accessflow.jwt.private-key", () -> pem);
    }

    @BeforeEach
    void setUp() {
        mvc = MockMvcTester.from(context, b -> b.apply(springSecurity()).build());
        // Defensive cleanup: other integration tests in this shared Spring context may leave
        // FK-bearing rows behind that would block organizationRepository.deleteAll().
        datasourceRepository.deleteAll();
        oauth2ConfigRepository.deleteAll();
        samlConfigRepository.deleteAll();
        apiKeyRepository.deleteAll();
        userRepository.deleteAll();
        organizationRepository.deleteAll();

        var org = new OrganizationEntity();
        org.setId(UUID.randomUUID());
        org.setName("Acme");
        org.setSlug("acme-" + UUID.randomUUID());
        organizationRepository.save(org);

        user = new UserEntity();
        user.setId(UUID.randomUUID());
        user.setEmail("alice@example.com");
        user.setDisplayName("Alice");
        user.setPasswordHash(passwordEncoder.encode("Password123!"));
        user.setRole(UserRoleType.ANALYST);
        user.setAuthProvider(AuthProviderType.LOCAL);
        user.setActive(true);
        user.setOrganization(org);
        userRepository.save(user);

        rawKey = apiKeyService.issue(user.getId(), org.getId(), "ci", null).rawKey();
    }

    @Test
    void x_api_key_header_authenticates_against_protected_endpoint() {
        var result = mvc.get().uri("/api/v1/me")
                .header(ApiKeyAuthenticationFilter.API_KEY_HEADER, rawKey)
                .exchange();
        assertThat(result).hasStatus(200);
        assertThat(result).bodyJson().extractingPath("$.email").asString()
                .isEqualTo("alice@example.com");
    }

    @Test
    void authorization_apikey_scheme_also_authenticates() {
        var result = mvc.get().uri("/api/v1/me")
                .header("Authorization", "ApiKey " + rawKey)
                .exchange();
        assertThat(result).hasStatus(200);
    }

    @Test
    void revoked_key_is_rejected() {
        var keyId = apiKeyService.list(user.getId()).get(0).id();
        apiKeyService.revoke(user.getId(), keyId);
        var result = mvc.get().uri("/api/v1/me")
                .header(ApiKeyAuthenticationFilter.API_KEY_HEADER, rawKey)
                .exchange();
        assertThat(result).hasStatus(401);
    }

    @Test
    void invalid_key_is_rejected() {
        var result = mvc.get().uri("/api/v1/me")
                .header(ApiKeyAuthenticationFilter.API_KEY_HEADER, "af_invalid")
                .exchange();
        assertThat(result).hasStatus(401);
    }

    @Test
    void no_credentials_returns_401() {
        var result = mvc.get().uri("/api/v1/me").exchange();
        assertThat(result).hasStatus(401);
    }
}
