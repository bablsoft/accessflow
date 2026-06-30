package com.bablsoft.accessflow.apigov.internal;

import com.bablsoft.accessflow.TestcontainersConfig;
import com.bablsoft.accessflow.apigov.api.ApiConnectorClassificationAdminService;
import com.bablsoft.accessflow.apigov.api.ApiConnectorMaskingAdminService;
import com.bablsoft.accessflow.apigov.api.ApiConnectorMaskingResolutionService;
import com.bablsoft.accessflow.apigov.api.ApiMaskingMatcherType;
import com.bablsoft.accessflow.apigov.api.ApiProtocol;
import com.bablsoft.accessflow.apigov.api.CreateApiConnectorClassificationTagCommand;
import com.bablsoft.accessflow.apigov.api.CreateApiConnectorMaskingPolicyCommand;
import com.bablsoft.accessflow.apigov.internal.persistence.entity.ApiConnectorEntity;
import com.bablsoft.accessflow.apigov.internal.persistence.repo.ApiConnectorMaskingPolicyRepository;
import com.bablsoft.accessflow.apigov.internal.persistence.repo.ApiConnectorRepository;
import com.bablsoft.accessflow.core.api.DataClassification;
import com.bablsoft.accessflow.core.api.MaskingStrategy;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateCrtKey;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Persistence + service integration for AF-518 against a real Postgres: connector masking policies
 * and classification tags (JSONB params, text[]/uuid[] arrays, enum columns), classification → masking
 * derivation, and reveal-precedence resolution.
 */
@SpringBootTest
@ImportTestcontainers(TestcontainersConfig.class)
class ApiConnectorMaskingClassificationIntegrationTest {

    @Autowired private ApiConnectorRepository connectorRepository;
    @Autowired private ApiConnectorMaskingAdminService maskingAdminService;
    @Autowired private ApiConnectorMaskingResolutionService resolutionService;
    @Autowired private ApiConnectorClassificationAdminService classificationService;
    @Autowired private ApiConnectorMaskingPolicyRepository policyRepository;

    private final UUID orgId = UUID.randomUUID();

    @DynamicPropertySource
    static void securityProperties(DynamicPropertyRegistry registry) throws Exception {
        var kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        var privateKey = (RSAPrivateCrtKey) kpg.generateKeyPair().getPrivate();
        var pem = "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(privateKey.getEncoded())
                + "\n-----END PRIVATE KEY-----";
        registry.add("accessflow.jwt.private-key", () -> pem);
        registry.add("accessflow.encryption-key", () ->
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef");
    }

    private UUID connector() {
        var c = new ApiConnectorEntity();
        c.setId(UUID.randomUUID());
        c.setOrganizationId(orgId);
        c.setName("conn-" + UUID.randomUUID());
        c.setProtocol(ApiProtocol.REST);
        c.setBaseUrl("https://api.test");
        return connectorRepository.save(c).getId();
    }

    @Test
    void maskingPolicyRoundTripsThroughPostgres() {
        var connectorId = connector();
        var view = maskingAdminService.create(connectorId, orgId,
                new CreateApiConnectorMaskingPolicyCommand(ApiMaskingMatcherType.JSON_PATH, null,
                        "user.ssn", MaskingStrategy.PARTIAL, Map.of("visible_suffix", "4"),
                        List.of("ADMIN"), List.of(), List.of(), true));

        var listed = maskingAdminService.listForConnector(connectorId, orgId);
        assertThat(listed).hasSize(1);
        assertThat(listed.getFirst().strategyParams()).containsEntry("visible_suffix", "4");
        assertThat(listed.getFirst().revealToRoles()).containsExactly("ADMIN");

        maskingAdminService.delete(view.id(), connectorId, orgId);
        assertThat(maskingAdminService.listForConnector(connectorId, orgId)).isEmpty();
    }

    @Test
    void classificationTagDerivesMaskingPolicy() {
        var connectorId = connector();
        var tags = classificationService.create(connectorId, orgId,
                new CreateApiConnectorClassificationTagCommand(ApiMaskingMatcherType.JSON_PATH, null,
                        "user.card", List.of(DataClassification.PCI), "card data", true));

        assertThat(tags).hasSize(1);
        // PCI auto-derives a FULL masking policy on the same field.
        var policies = policyRepository
                .findAllByOrganizationIdAndConnectorIdAndEnabledTrue(orgId, connectorId);
        assertThat(policies).hasSize(1);
        assertThat(policies.getFirst().getStrategy()).isEqualTo(MaskingStrategy.FULL);
        assertThat(policies.getFirst().getFieldRef()).isEqualTo("user.card");

        var derivation = classificationService.previewDerivation(connectorId, orgId);
        assertThat(derivation.suggestedReviewPosture().minApprovals()).isEqualTo(2);
        assertThat(derivation.maskingSuggestions().getFirst().alreadyApplied()).isTrue();
    }

    @Test
    void resolutionRespectsRoleReveal() {
        var connectorId = connector();
        maskingAdminService.create(connectorId, orgId,
                new CreateApiConnectorMaskingPolicyCommand(ApiMaskingMatcherType.JSON_PATH, null,
                        "email", MaskingStrategy.FULL, Map.of(), List.of("ADMIN"), List.of(), List.of(), true));

        // A random user (no persisted user row → null role, no groups) is not revealed → mask applies.
        var resolved = resolutionService.resolveApplicable(orgId, connectorId, UUID.randomUUID());
        assertThat(resolved).hasSize(1);
        assertThat(resolved.getFirst().fieldRef()).isEqualTo("email");
    }
}
