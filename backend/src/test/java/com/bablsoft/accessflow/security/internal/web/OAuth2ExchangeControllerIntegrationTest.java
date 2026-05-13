package com.bablsoft.accessflow.security.internal.web;

import com.bablsoft.accessflow.TestcontainersConfig;
import com.bablsoft.accessflow.core.api.AuthProviderType;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.core.internal.persistence.entity.OrganizationEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.UserEntity;
import com.bablsoft.accessflow.core.internal.persistence.repo.OrganizationRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.UserRepository;
import com.bablsoft.accessflow.security.internal.oauth2.OAuth2ExchangeCodeStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.http.MediaType;
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

@SpringBootTest
@ImportTestcontainers(TestcontainersConfig.class)
class OAuth2ExchangeControllerIntegrationTest {

    @Autowired WebApplicationContext context;
    @Autowired UserRepository userRepository;
    @Autowired OrganizationRepository organizationRepository;
    @Autowired OAuth2ExchangeCodeStore codeStore;

    private MockMvcTester mvc;
    private OrganizationEntity org;
    private UserEntity user;

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
        registry.add("accessflow.audit.hmac-key", () ->
                "abababababababababababababababababababababababababababababababab");
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
        organizationRepository.save(org);

        user = new UserEntity();
        user.setId(UUID.randomUUID());
        user.setEmail("u@example.com");
        user.setDisplayName("U");
        user.setRole(UserRoleType.ANALYST);
        user.setAuthProvider(AuthProviderType.OAUTH2);
        user.setActive(true);
        user.setOrganization(org);
        userRepository.save(user);
    }

    @AfterEach
    void cleanup() {
        userRepository.deleteAll();
    }

    @Test
    void exchangeReturnsLoginPayloadAndSetsRefreshCookie() {
        var code = codeStore.issue(user.getId());

        var result = mvc.post().uri("/api/v1/auth/oauth2/exchange")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"" + code + "\"}")
                .exchange();

        assertThat(result).hasStatus(200);
        assertThat(result).bodyJson().extractingPath("$.access_token").asString().isNotBlank();
        assertThat(result).bodyJson().extractingPath("$.user.email").asString().isEqualTo("u@example.com");
        assertThat(result.getResponse().getHeader("Set-Cookie")).startsWith("refresh_token=");
    }

    @Test
    void exchangeReturns401ForUnknownCode() {
        var result = mvc.post().uri("/api/v1/auth/oauth2/exchange")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"never-issued\"}")
                .exchange();

        assertThat(result).hasStatus(401);
    }

    @Test
    void exchangeReturns400ForBlankCode() {
        var result = mvc.post().uri("/api/v1/auth/oauth2/exchange")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"\"}")
                .exchange();

        assertThat(result).hasStatus(400);
    }

    @Test
    void exchangeReturns401WhenCodeAlreadyConsumed() {
        var code = codeStore.issue(user.getId());
        mvc.post().uri("/api/v1/auth/oauth2/exchange")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"" + code + "\"}")
                .exchange();

        var second = mvc.post().uri("/api/v1/auth/oauth2/exchange")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"" + code + "\"}")
                .exchange();
        assertThat(second).hasStatus(401);
    }
}
