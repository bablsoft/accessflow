package com.bablsoft.accessflow.core.internal;

import com.bablsoft.accessflow.TestcontainersConfig;
import com.bablsoft.accessflow.core.api.SystemRolePermissions;
import com.bablsoft.accessflow.core.api.UserRoleType;
import com.bablsoft.accessflow.core.internal.persistence.entity.RolePermissionEntity;
import com.bablsoft.accessflow.core.internal.persistence.repo.RolePermissionRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.RoleRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateCrtKey;
import java.util.Base64;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the AF-522 invariant that the V114 system-role seed rows stay identical to the
 * authoritative code map ({@link SystemRolePermissions}). Runtime resolution answers system roles
 * from the code map, so a drifted seed would silently mislead the roles UI / catalog API.
 */
@SpringBootTest
@ImportTestcontainers(TestcontainersConfig.class)
class SystemRoleSeedParityIntegrationTest {

    @Autowired RoleRepository roleRepository;
    @Autowired RolePermissionRepository rolePermissionRepository;

    @DynamicPropertySource
    static void securityProperties(DynamicPropertyRegistry registry) throws Exception {
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

    @Test
    void everySystemRoleIsSeededGloballyWithTheCodeMapPermissionSet() {
        for (var role : UserRoleType.values()) {
            var entity = roleRepository.findByNameAndSystemTrue(role.name()).orElseThrow(
                    () -> new AssertionError("System role not seeded: " + role));
            assertThat(entity.isSystem()).isTrue();
            assertThat(entity.getOrganization()).isNull();

            var seeded = rolePermissionRepository.findAllByRole_Id(entity.getId()).stream()
                    .map(RolePermissionEntity::getPermission)
                    .collect(Collectors.toSet());
            assertThat(seeded)
                    .as("V114 seed for %s must mirror SystemRolePermissions", role)
                    .isEqualTo(SystemRolePermissions.of(role));
        }
    }
}
