package com.bablsoft.accessflow.api.internal.web;

import com.bablsoft.accessflow.TestcontainersConfig;
import com.bablsoft.accessflow.api.internal.UpdateCheckService;
import com.bablsoft.accessflow.api.internal.UpdateCheckStatus;
import com.bablsoft.accessflow.api.internal.UpdateStatusView;
import com.bablsoft.accessflow.core.api.AuthProviderType;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.core.api.UserView;
import com.bablsoft.accessflow.core.internal.persistence.entity.OrganizationEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.UserEntity;
import com.bablsoft.accessflow.core.internal.persistence.repo.OrganizationRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.UserRepository;
import com.bablsoft.accessflow.security.internal.jwt.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.web.context.WebApplicationContext;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

@SpringBootTest
@ImportTestcontainers(TestcontainersConfig.class)
class SystemUpdateStatusControllerIntegrationTest {

    private static final String PATH = "/api/v1/system/update-status";

    @Autowired WebApplicationContext context;
    @Autowired UserRepository userRepository;
    @Autowired OrganizationRepository organizationRepository;
    @Autowired JwtService jwtService;
    @MockitoBean UpdateCheckService updateCheckService;

    private MockMvcTester mvc;
    private String adminToken;
    private String readonlyToken;

    @BeforeEach
    void setUp() {
        mvc = MockMvcTester.from(context, builder -> builder.apply(springSecurity()).build());
        var org = new OrganizationEntity();
        org.setId(UUID.randomUUID());
        org.setName("Update Status Org " + UUID.randomUUID());
        org.setSlug("update-status-" + UUID.randomUUID());
        organizationRepository.save(org);
        adminToken = generateToken(saveUser(org, UserRoleType.ADMIN));
        readonlyToken = generateToken(saveUser(org, UserRoleType.READONLY));
    }

    @Test
    void returnsTheSnapshotForAnAdmin() {
        when(updateCheckService.status()).thenReturn(new UpdateStatusView("2.4.0", "2.5.0", true,
                "https://accessflow.io/changelog/#v2-5-0", Instant.parse("2026-09-20T08:00:00Z"),
                UpdateCheckStatus.UPDATE_AVAILABLE));

        var result = mvc.get().uri(PATH).header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken).exchange();

        assertThat(result).hasStatus(200);
        assertThat(result).bodyJson().extractingPath("$.current_version").isEqualTo("2.4.0");
        assertThat(result).bodyJson().extractingPath("$.latest_version").isEqualTo("2.5.0");
        assertThat(result).bodyJson().extractingPath("$.update_available").asBoolean().isTrue();
        assertThat(result).bodyJson().extractingPath("$.changelog_url")
                .isEqualTo("https://accessflow.io/changelog/#v2-5-0");
        assertThat(result).bodyJson().extractingPath("$.checked_at").isEqualTo("2026-09-20T08:00:00Z");
        assertThat(result).bodyJson().extractingPath("$.status").isEqualTo("UPDATE_AVAILABLE");
    }

    @Test
    void isReadableByEverySignedInUserNotOnlyAdmins() {
        when(updateCheckService.status()).thenReturn(UpdateStatusView.unknown("1.0.0-SNAPSHOT", null));

        var result = mvc.get().uri(PATH).header(HttpHeaders.AUTHORIZATION, "Bearer " + readonlyToken).exchange();

        assertThat(result).hasStatus(200);
        assertThat(result).bodyJson().extractingPath("$.status").isEqualTo("UNKNOWN");
        assertThat(result).bodyJson().extractingPath("$.update_available").asBoolean().isFalse();
        // The API serializes with non_null inclusion, so an unknown snapshot omits these entirely.
        assertThat(result).bodyJson().doesNotHavePath("$.latest_version");
        assertThat(result).bodyJson().doesNotHavePath("$.changelog_url");
        assertThat(result).bodyJson().doesNotHavePath("$.checked_at");
    }

    @Test
    void rejectsUnauthenticatedCallers() {
        var result = mvc.get().uri(PATH).exchange();

        assertThat(result).hasStatus(401);
    }

    private UserEntity saveUser(OrganizationEntity org, UserRoleType role) {
        var user = new UserEntity();
        user.setId(UUID.randomUUID());
        user.setEmail(role.name().toLowerCase() + "-" + UUID.randomUUID() + "@example.com");
        user.setDisplayName(role.name());
        user.setPasswordHash("hashed");
        user.setRole(role);
        user.setAuthProvider(AuthProviderType.LOCAL);
        user.setActive(true);
        user.setOrganization(org);
        return userRepository.save(user);
    }

    private String generateToken(UserEntity entity) {
        var view = new UserView(
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
