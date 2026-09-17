package com.bablsoft.accessflow.security.internal.web;

import com.bablsoft.accessflow.TestcontainersConfig;
import com.bablsoft.accessflow.core.api.AuthProviderType;
import com.bablsoft.accessflow.core.api.PrincipalType;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.core.internal.persistence.entity.OrganizationEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.UserEntity;
import com.bablsoft.accessflow.core.internal.persistence.repo.OrganizationRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.UserRepository;
import com.bablsoft.accessflow.security.internal.saml.SamlExchangeCodeStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.web.context.WebApplicationContext;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

/**
 * The SAML half of #869's "no interactive session for a service account" proof: the exchange
 * endpoint is where a SAML redirect turns into a JWT pair, and it must refuse a service account
 * even when a code was minted for it. Mirrors {@link OAuth2ExchangeControllerIntegrationTest}.
 */
@SpringBootTest
@ImportTestcontainers(TestcontainersConfig.class)
class SamlExchangeControllerIntegrationTest {

    @Autowired WebApplicationContext context;
    @Autowired UserRepository userRepository;
    @Autowired OrganizationRepository organizationRepository;
    @Autowired SamlExchangeCodeStore codeStore;

    private MockMvcTester mvc;
    private OrganizationEntity org;

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
    }

    @AfterEach
    void cleanup() {
        userRepository.deleteAll();
    }

    @Test
    void exchangeForHumanReturnsLoginPayloadAndSetsRefreshCookie() {
        var human = seedUser("u@example.com", PrincipalType.HUMAN);
        var code = codeStore.issue(human.getId());

        var result = mvc.post().uri("/api/v1/auth/saml/exchange")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"" + code + "\"}")
                .exchange();

        assertThat(result).hasStatus(200);
        assertThat(result).bodyJson().extractingPath("$.access_token").asString().isNotBlank();
        assertThat(result).bodyJson().extractingPath("$.user.email").asString().isEqualTo("u@example.com");
        assertThat(result.getResponse().getHeader("Set-Cookie")).startsWith("refresh_token=");
    }

    @Test
    void exchangeForServiceAccountReturns401WithDistinctCodeAndNoCookie() {
        var bot = seedUser("ci-bot@example.com", PrincipalType.SERVICE_ACCOUNT);
        var code = codeStore.issue(bot.getId());

        var result = mvc.post().uri("/api/v1/auth/saml/exchange")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"" + code + "\"}")
                .exchange();

        assertThat(result).hasStatus(401);
        assertThat(result).bodyJson().extractingPath("$.error").asString()
                .isEqualTo("SERVICE_ACCOUNT_SIGN_IN_BLOCKED");
        assertThat(result.getResponse().getHeader("Set-Cookie")).isNull();
    }

    @Test
    void exchangeReturns401ForUnknownCode() {
        var result = mvc.post().uri("/api/v1/auth/saml/exchange")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"never-issued\"}")
                .exchange();

        assertThat(result).hasStatus(401);
    }

    private UserEntity seedUser(String email, PrincipalType principalType) {
        var user = new UserEntity();
        user.setId(UUID.randomUUID());
        user.setEmail(email);
        user.setDisplayName("U");
        user.setRole(UserRoleType.ANALYST);
        user.setAuthProvider(AuthProviderType.SAML);
        user.setActive(true);
        user.setOrganization(org);
        user.setPrincipalType(principalType);
        return userRepository.save(user);
    }
}
