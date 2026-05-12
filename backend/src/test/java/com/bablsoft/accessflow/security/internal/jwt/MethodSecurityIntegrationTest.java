package com.bablsoft.accessflow.security.internal.jwt;

import com.bablsoft.accessflow.TestcontainersConfig;
import com.bablsoft.accessflow.core.api.AuthProviderType;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.core.api.UserView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.http.HttpHeaders;
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

@SpringBootTest(properties = "accessflow.test.method-security=true")
@ImportTestcontainers(TestcontainersConfig.class)
class MethodSecurityIntegrationTest {

    private static final String[] ALL_PATHS = {
            "/test/method-security/admin",
            "/test/method-security/reviewer",
            "/test/method-security/analyst",
            "/test/method-security/readonly"
    };

    @Autowired WebApplicationContext context;
    @Autowired JwtService jwtService;

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
    }

    @Test
    void unauthenticatedRequestToProtectedEndpointsReturns401() {
        for (var path : ALL_PATHS) {
            assertThat(mvc.get().uri(path).exchange()).hasStatus(401);
        }
    }

    @Test
    void adminTokenAccessesOnlyAdminEndpoint() {
        assertRoleAuthorityMatrix(UserRoleType.ADMIN, "/test/method-security/admin");
    }

    @Test
    void reviewerTokenAccessesOnlyReviewerEndpoint() {
        assertRoleAuthorityMatrix(UserRoleType.REVIEWER, "/test/method-security/reviewer");
    }

    @Test
    void analystTokenAccessesOnlyAnalystEndpoint() {
        assertRoleAuthorityMatrix(UserRoleType.ANALYST, "/test/method-security/analyst");
    }

    @Test
    void readonlyTokenAccessesOnlyReadonlyEndpoint() {
        assertRoleAuthorityMatrix(UserRoleType.READONLY, "/test/method-security/readonly");
    }

    private void assertRoleAuthorityMatrix(UserRoleType role, String allowedPath) {
        var token = jwtService.generateAccessToken(new UserView(
                UUID.randomUUID(),
                "test@example.com",
                "Test User",
                role,
                UUID.randomUUID(),
                true,
                AuthProviderType.LOCAL,
                null,
                null,
                null,
                false,
                null
        ));
        for (var path : ALL_PATHS) {
            var result = mvc.get().uri(path)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .exchange();
            var expectedStatus = path.equals(allowedPath) ? 200 : 403;
            assertThat(result.getResponse().getStatus())
                    .as("role=%s path=%s body=%s", role, path, safeBody(result))
                    .isEqualTo(expectedStatus);
        }
    }

    private static String safeBody(org.springframework.test.web.servlet.assertj.MvcTestResult result) {
        try {
            return result.getResponse().getContentAsString();
        } catch (Exception e) {
            return "<unreadable>";
        }
    }
}
