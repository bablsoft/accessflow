package com.bablsoft.accessflow.security.internal.web;

import com.bablsoft.accessflow.TestcontainersConfig;
import com.bablsoft.accessflow.core.api.AuthProviderType;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.core.internal.persistence.entity.OrganizationEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.UserEntity;
import com.bablsoft.accessflow.core.internal.persistence.repo.OrganizationRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.UserRepository;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
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
class AuthControllerIntegrationTest {

    @Autowired WebApplicationContext context;
    @Autowired UserRepository userRepository;
    @Autowired OrganizationRepository organizationRepository;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired com.bablsoft.accessflow.core.api.CredentialEncryptionService encryptionService;

    private MockMvcTester mvc;

    private static final String EMAIL = "integration@example.com";
    private static final String PASSWORD = "Password123!";

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
    }

    @BeforeEach
    void setUp() {
        mvc = MockMvcTester.from(context, builder -> builder.apply(springSecurity()).build());

        userRepository.deleteAll();
        organizationRepository.deleteAll();

        var org = new OrganizationEntity();
        org.setId(UUID.randomUUID());
        org.setName("Test Org");
        org.setSlug("test-org");
        organizationRepository.save(org);

        var user = new UserEntity();
        user.setId(UUID.randomUUID());
        user.setEmail(EMAIL);
        user.setDisplayName("Integration User");
        user.setPasswordHash(passwordEncoder.encode(PASSWORD));
        user.setRole(UserRoleType.ANALYST);
        user.setAuthProvider(AuthProviderType.LOCAL);
        user.setActive(true);
        user.setOrganization(org);
        userRepository.save(user);
    }

    @Test
    void loginWithValidCredentialsReturns200WithAccessToken() {
        var result = mvc.post().uri("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email":"%s","password":"%s"}
                        """.formatted(EMAIL, PASSWORD))
                .exchange();

        assertThat(result).hasStatus(200);
        assertThat(result).bodyJson().extractingPath("$.access_token").asString().isNotBlank();
        assertThat(result).bodyJson().extractingPath("$.token_type").asString().isEqualTo("Bearer");
        assertThat(result.getResponse().getHeader(HttpHeaders.SET_COOKIE)).contains("refresh_token=");
        assertThat(result.getResponse().getHeader(HttpHeaders.SET_COOKIE)).contains("HttpOnly");
    }

    @Test
    void loginWithWrongPasswordReturns401() {
        var result = mvc.post().uri("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email":"%s","password":"wrongpassword"}
                        """.formatted(EMAIL))
                .exchange();

        assertThat(result).hasStatus(401);
    }

    @Test
    void loginWithInvalidBodyReturns400() {
        var result = mvc.post().uri("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email":"not-an-email","password":"short"}
                        """)
                .exchange();

        assertThat(result).hasStatus(400);
    }

    @Test
    void refreshWithValidCookieReturns200AndIssuesNewAccessToken() {
        var loginResult = mvc.post().uri("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email":"%s","password":"%s"}
                        """.formatted(EMAIL, PASSWORD))
                .exchange();
        var loginCookie = loginResult.getResponse().getHeader(HttpHeaders.SET_COOKIE);
        var originalRefreshToken = extractRefreshToken(loginCookie);

        var refreshResult = mvc.post().uri("/api/v1/auth/refresh")
                .cookie(new Cookie("refresh_token", originalRefreshToken))
                .exchange();

        assertThat(refreshResult).hasStatus(200);
        assertThat(refreshResult).bodyJson().extractingPath("$.access_token").asString().isNotBlank();
        var rotatedCookie = refreshResult.getResponse().getHeader(HttpHeaders.SET_COOKIE);
        assertThat(rotatedCookie).contains("refresh_token=").contains("Path=/api/v1/auth");
    }

    @Test
    void refreshWithoutCookieReturns401() {
        var result = mvc.post().uri("/api/v1/auth/refresh").exchange();

        assertThat(result).hasStatus(401);
    }

    @Test
    void logoutReturns204AndClearsCookie() {
        var loginResult = mvc.post().uri("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email":"%s","password":"%s"}
                        """.formatted(EMAIL, PASSWORD))
                .exchange();
        var cookie = loginResult.getResponse().getHeader(HttpHeaders.SET_COOKIE);

        var logoutResult = mvc.post().uri("/api/v1/auth/logout")
                .header(HttpHeaders.COOKIE, cookie)
                .exchange();

        assertThat(logoutResult).hasStatus(204);
        assertThat(logoutResult.getResponse().getHeader(HttpHeaders.SET_COOKIE)).contains("Max-Age=0");
    }

    private static String extractRefreshToken(String setCookieHeader) {
        for (var part : setCookieHeader.split(";")) {
            var trimmed = part.trim();
            if (trimmed.startsWith("refresh_token=")) {
                return trimmed.substring("refresh_token=".length());
            }
        }
        throw new AssertionError("refresh_token not found in: " + setCookieHeader);
    }

    @Test
    void loginWith2faEnabledAndNoCodeReturns401TotpRequired() {
        enableTotpOnTestUser();

        var result = mvc.post().uri("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email":"%s","password":"%s"}
                        """.formatted(EMAIL, PASSWORD))
                .exchange();

        assertThat(result).hasStatus(401);
        assertThat(result).bodyJson().extractingPath("$.error").asString().isEqualTo("TOTP_REQUIRED");
    }

    @Test
    void loginWith2faEnabledAndBadCodeReturns401TotpInvalid() {
        enableTotpOnTestUser();

        var result = mvc.post().uri("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email":"%s","password":"%s","totp_code":"000000"}
                        """.formatted(EMAIL, PASSWORD))
                .exchange();

        assertThat(result).hasStatus(401);
        assertThat(result).bodyJson().extractingPath("$.error").asString().isEqualTo("TOTP_INVALID");
    }

    private void enableTotpOnTestUser() {
        var user = userRepository.findByEmail(EMAIL).orElseThrow();
        user.setTotpEnabled(true);
        // Encrypt a real (base32) secret so the decrypt step in DefaultTotpVerificationService
        // doesn't blow up on malformed ciphertext. The negative-path tests only need the
        // verifier to reach the "not matching" branch — they don't need a valid code.
        user.setTotpSecretEncrypted(encryptionService.encrypt("JBSWY3DPEHPK3PXP"));
        userRepository.save(user);
    }

    @Test
    void protectedEndpointWithoutTokenReturns401() {
        var result = mvc.get().uri("/api/v1/datasources").exchange();

        assertThat(result).hasStatus(401);
    }

    @Test
    void protectedEndpointWithValidTokenIsNotRejected() throws Exception {
        var loginResult = mvc.post().uri("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email":"%s","password":"%s"}
                        """.formatted(EMAIL, PASSWORD))
                .exchange();
        var body = loginResult.getResponse().getContentAsString();
        var token = body.replaceAll(".*\"access_token\":\"([^\"]+)\".*", "$1");

        var result = mvc.get().uri("/api/v1/datasources")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange();

        assertThat(result.getResponse().getStatus())
                .isNotEqualTo(401)
                .isNotEqualTo(403);
    }
}
