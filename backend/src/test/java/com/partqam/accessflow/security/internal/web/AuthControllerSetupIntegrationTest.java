package com.partqam.accessflow.security.internal.web;

import com.partqam.accessflow.TestcontainersConfig;
import com.partqam.accessflow.core.api.UserRoleType;
import com.partqam.accessflow.core.internal.persistence.repo.OrganizationRepository;
import com.partqam.accessflow.core.internal.persistence.repo.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.web.context.WebApplicationContext;

import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateCrtKey;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

@SpringBootTest
@ImportTestcontainers(TestcontainersConfig.class)
class AuthControllerSetupIntegrationTest {

    @Autowired WebApplicationContext context;
    @Autowired UserRepository userRepository;
    @Autowired OrganizationRepository organizationRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    private MockMvcTester mvc;

    @DynamicPropertySource
    static void rsaProperties(DynamicPropertyRegistry registry) throws Exception {
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
        // Give every test a clean slate. The audit_log table has FK-less columns
        // for actor/organization but we still wipe rows we may have written.
        jdbcTemplate.update("DELETE FROM audit_log");
        userRepository.deleteAll();
        organizationRepository.deleteAll();
    }

    @Test
    void setupStatusReturnsTrueOnEmptyDatabase() {
        var result = mvc.get().uri("/api/v1/auth/setup-status").exchange();

        assertThat(result).hasStatus(200);
        assertThat(result).bodyJson().extractingPath("$.setup_required").asBoolean().isTrue();
    }

    @Test
    void setupCreatesOrganizationAndAdminUserAndFlipsStatus() {
        var setupResult = mvc.post().uri("/api/v1/auth/setup")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {
                          "organization_name": "Acme",
                          "email": "admin@acme.com",
                          "display_name": "Acme Admin",
                          "password": "secret-pass-12"
                        }
                        """)
                .exchange();

        assertThat(setupResult).hasStatus(201);

        assertThat(organizationRepository.findAll()).singleElement()
                .satisfies(org -> {
                    assertThat(org.getName()).isEqualTo("Acme");
                    assertThat(org.getSlug()).isEqualTo("acme");
                });
        assertThat(userRepository.findByEmail("admin@acme.com")).isPresent()
                .get()
                .satisfies(user -> {
                    assertThat(user.getRole()).isEqualTo(UserRoleType.ADMIN);
                    assertThat(user.isActive()).isTrue();
                    assertThat(user.getPasswordHash()).isNotEqualTo("secret-pass-12");
                });

        var statusResult = mvc.get().uri("/api/v1/auth/setup-status").exchange();
        assertThat(statusResult).hasStatus(200);
        assertThat(statusResult).bodyJson().extractingPath("$.setup_required").asBoolean().isFalse();
    }

    @Test
    void setupRunTwiceReturnsConflict() {
        mvc.post().uri("/api/v1/auth/setup")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {
                          "organization_name": "Acme",
                          "email": "admin@acme.com",
                          "display_name": "Acme Admin",
                          "password": "secret-pass-12"
                        }
                        """)
                .exchange();

        var second = mvc.post().uri("/api/v1/auth/setup")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {
                          "organization_name": "Other Co",
                          "email": "other@example.com",
                          "display_name": "Other",
                          "password": "another-pass-12"
                        }
                        """)
                .exchange();

        assertThat(second).hasStatus(409);
        assertThat(second).bodyJson().extractingPath("$.error").asString()
                .isEqualTo("SETUP_ALREADY_COMPLETED");
    }

    @Test
    void setupRejectsInvalidBody() {
        var result = mvc.post().uri("/api/v1/auth/setup")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {
                          "organization_name": "",
                          "email": "not-an-email",
                          "password": "short"
                        }
                        """)
                .exchange();

        assertThat(result).hasStatus(400);
    }

    @Test
    void setupEndpointsAreReachableWithoutAuthentication() {
        // Sanity check: the SecurityConfiguration permits both endpoints. If this regresses,
        // the requests above would already 401 — but a dedicated assertion makes the gate clear.
        assertThat(mvc.get().uri("/api/v1/auth/setup-status").exchange())
                .hasStatus(200);

        var result = mvc.post().uri("/api/v1/auth/setup")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {
                          "organization_name": "Acme",
                          "email": "admin@acme.com",
                          "display_name": "Acme Admin",
                          "password": "secret-pass-12"
                        }
                        """)
                .exchange();
        assertThat(result.getResponse().getStatus()).isNotEqualTo(401).isNotEqualTo(403);
    }
}
