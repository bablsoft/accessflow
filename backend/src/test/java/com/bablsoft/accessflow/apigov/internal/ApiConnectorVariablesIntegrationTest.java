package com.bablsoft.accessflow.apigov.internal;

import com.bablsoft.accessflow.TestcontainersConfig;
import com.bablsoft.accessflow.apigov.api.ApiConnectorVariableAdminService;
import com.bablsoft.accessflow.apigov.api.ApiConnectorVariableLookupService;
import com.bablsoft.accessflow.apigov.api.ApiConnectorVariableResolutionService;
import com.bablsoft.accessflow.apigov.api.ApiProtocol;
import com.bablsoft.accessflow.apigov.api.ApiVariableAlgorithm;
import com.bablsoft.accessflow.apigov.api.ApiVariableEncoding;
import com.bablsoft.accessflow.apigov.api.ApiVariableKind;
import com.bablsoft.accessflow.apigov.api.ApiVariableRequestContext;
import com.bablsoft.accessflow.apigov.api.ApiVariableTargetType;
import com.bablsoft.accessflow.apigov.api.CreateApiConnectorVariableCommand;
import com.bablsoft.accessflow.apigov.api.IllegalApiConnectorVariableException;
import com.bablsoft.accessflow.apigov.api.ReorderApiConnectorVariablesCommand;
import com.bablsoft.accessflow.apigov.api.UpdateApiConnectorVariableCommand;
import com.bablsoft.accessflow.apigov.internal.persistence.entity.ApiConnectorEntity;
import com.bablsoft.accessflow.apigov.internal.persistence.repo.ApiConnectorRepository;
import com.bablsoft.accessflow.apigov.internal.persistence.repo.ApiConnectorVariableRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateCrtKey;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Persistence + service integration for AF-613 against a real Postgres: the {@code api_variable_*}
 * enum columns, the unique name index, the overridable/secret CHECK constraint, and the full
 * resolve-and-sign path including the motivating vendor HMAC scheme.
 */
@SpringBootTest
@ImportTestcontainers(TestcontainersConfig.class)
class ApiConnectorVariablesIntegrationTest {

    @Autowired private ApiConnectorRepository connectorRepository;
    @Autowired private ApiConnectorVariableRepository variableRepository;
    @Autowired private ApiConnectorVariableAdminService adminService;
    @Autowired private ApiConnectorVariableResolutionService resolutionService;
    @Autowired private ApiConnectorVariableLookupService lookupService;

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

    private static CreateApiConnectorVariableCommand constant(String name, String expression) {
        return new CreateApiConnectorVariableCommand(name, ApiVariableKind.CONSTANT, expression,
                null, null, null, null, null, null, null);
    }

    @Test
    void variableRoundTripsThroughPostgresWithItsEnumColumns() {
        var connectorId = connector();

        var created = adminService.create(connectorId, orgId, new CreateApiConnectorVariableCommand(
                "signature", ApiVariableKind.HMAC, "{{request.body}}",
                ApiVariableAlgorithm.HMAC_SHA512, ApiVariableEncoding.BASE64URL, "shared-key",
                "header:X-Signature", false, "Vendor signature", null));

        var listed = adminService.listForConnector(connectorId, orgId);
        assertThat(listed).singleElement().satisfies(v -> {
            assertThat(v.id()).isEqualTo(created.id());
            assertThat(v.kind()).isEqualTo(ApiVariableKind.HMAC);
            assertThat(v.algorithm()).isEqualTo(ApiVariableAlgorithm.HMAC_SHA512);
            assertThat(v.encoding()).isEqualTo(ApiVariableEncoding.BASE64URL);
            assertThat(v.target()).isEqualTo("header:X-Signature");
            assertThat(v.hasSecret()).isTrue();
        });
    }

    /** The stored secret is AES-256-GCM ciphertext, never the plaintext key. */
    @Test
    void theSecretIsEncryptedAtRest() {
        var connectorId = connector();
        var created = adminService.create(connectorId, orgId, new CreateApiConnectorVariableCommand(
                "sig", ApiVariableKind.HMAC, "{{request.body}}", ApiVariableAlgorithm.HMAC_SHA256,
                ApiVariableEncoding.HEX, "shared-key", null, false, null, null));

        var stored = variableRepository.findById(created.id()).orElseThrow();

        assertThat(stored.getSecretEncrypted()).isNotNull().isNotEqualTo("shared-key");
    }

    @Test
    void theUniqueNameIndexRejectsADuplicatePerConnector() {
        var connectorId = connector();
        adminService.create(connectorId, orgId, constant("dup", "a"));

        assertThatThrownBy(() -> adminService.create(connectorId, orgId, constant("dup", "b")))
                .isInstanceOf(IllegalApiConnectorVariableException.class);
    }

    @Test
    void theSameNameIsAllowedOnADifferentConnector() {
        adminService.create(connector(), orgId, constant("shared", "a"));

        assertThat(adminService.create(connector(), orgId, constant("shared", "b")).name())
                .isEqualTo("shared");
    }

    @Test
    void updateAndDeleteRoundTrip() {
        var connectorId = connector();
        var created = adminService.create(connectorId, orgId, constant("v", "one"));

        adminService.update(created.id(), connectorId, orgId, new UpdateApiConnectorVariableCommand(
                "v", ApiVariableKind.CONSTANT, "two", null, null, null, null, null, null, "note", null));
        assertThat(adminService.listForConnector(connectorId, orgId).getFirst().expression())
                .isEqualTo("two");

        adminService.delete(created.id(), connectorId, orgId);
        assertThat(adminService.listForConnector(connectorId, orgId)).isEmpty();
    }

    @Test
    void reorderPersistsTheNewEvaluationOrder() {
        var connectorId = connector();
        var a = adminService.create(connectorId, orgId, constant("a", "1"));
        var b = adminService.create(connectorId, orgId, constant("b", "2"));

        adminService.reorder(connectorId, orgId,
                new ReorderApiConnectorVariablesCommand(List.of(b.id(), a.id())));

        assertThat(adminService.listForConnector(connectorId, orgId))
                .extracting(v -> v.name()).containsExactly("b", "a");
    }

    /**
     * The database enforces the overridable-implies-no-secret rule with a CHECK constraint, so it
     * survives a manual data fix that bypasses the service.
     */
    @Test
    void theDatabaseRejectsAnOverridableSecretBearingRow() {
        var connectorId = connector();
        var created = adminService.create(connectorId, orgId, new CreateApiConnectorVariableCommand(
                "sig", ApiVariableKind.HMAC, "{{request.body}}", ApiVariableAlgorithm.HMAC_SHA256,
                ApiVariableEncoding.HEX, "k", null, false, null, null));
        var entity = variableRepository.findById(created.id()).orElseThrow();
        entity.setOverridable(true);

        assertThatThrownBy(() -> variableRepository.saveAndFlush(entity))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    /**
     * The motivating vendor scheme end to end, through real persistence and real AES decryption of
     * the stored key: HMAC-SHA256 over the Authorization header concatenated with the body, where
     * the body still carries the literal placeholder.
     */
    @Test
    void resolvesTheVendorHmacOverTheAuthHeaderAndPlaceholderBody() throws Exception {
        var connectorId = connector();
        adminService.create(connectorId, orgId, new CreateApiConnectorVariableCommand("signature",
                ApiVariableKind.HMAC, "{{request.headers.Authorization}}{{request.body}}",
                ApiVariableAlgorithm.HMAC_SHA256, ApiVariableEncoding.HEX, "shared-key", null,
                false, null, null));

        var body = "{\"data\":\"example_data\",\"HMAC\":\"{{signature}}\"}";
        var authHeader = "Basic token_value";
        var resolved = resolutionService.resolve(orgId, connectorId,
                new ApiVariableRequestContext("POST", "/pay", "", body,
                        Map.of("Authorization", authHeader)),
                Map.of());

        var mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec("shared-key".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        var expected = HexFormat.of()
                .formatHex(mac.doFinal((authHeader + body).getBytes(StandardCharsets.UTF_8)));

        assertThat(resolved.values()).containsEntry("signature", expected);
    }

    @Test
    void resolvesAChainInDependencyOrderAndEmitsItsTargets() {
        var connectorId = connector();
        adminService.create(connectorId, orgId, new CreateApiConnectorVariableCommand("ts",
                ApiVariableKind.EPOCH_MILLIS, null, null, null, null, "header:X-Timestamp", false,
                null, null));
        adminService.create(connectorId, orgId, constant("payload", "v1|{{ts}}"));

        var resolved = resolutionService.resolve(orgId, connectorId,
                new ApiVariableRequestContext("GET", "/x", "", "", Map.of()), Map.of());

        assertThat(resolved.values().get("payload")).startsWith("v1|")
                .isEqualTo("v1|" + resolved.values().get("ts"));
        assertThat(resolved.injections()).singleElement().satisfies(i -> {
            assertThat(i.type()).isEqualTo(ApiVariableTargetType.HEADER);
            assertThat(i.key()).isEqualTo("X-Timestamp");
        });
    }

    @Test
    void lookupServiceReportsOnlyTheOverridableNames() {
        var connectorId = connector();
        adminService.create(connectorId, orgId, new CreateApiConnectorVariableCommand("nonce",
                ApiVariableKind.RANDOM_HEX, null, null, null, null, null, true, "Client nonce", null));
        adminService.create(connectorId, orgId, constant("locked", "x"));

        assertThat(lookupService.overridableNames(connectorId, orgId)).containsExactly("nonce");
        assertThat(lookupService.summariesForConnector(connectorId, orgId))
                .extracting(s -> s.name()).containsExactly("nonce", "locked");
    }

    @Test
    void anOverrideReplacesTheGeneratedValue() {
        var connectorId = connector();
        adminService.create(connectorId, orgId, new CreateApiConnectorVariableCommand("nonce",
                ApiVariableKind.RANDOM_HEX, null, null, null, null, null, true, null, null));

        var resolved = resolutionService.resolve(orgId, connectorId,
                new ApiVariableRequestContext("GET", "/x", "", "", Map.of()),
                Map.of("nonce", "supplied"));

        assertThat(resolved.values()).containsEntry("nonce", "supplied");
    }

    @Test
    void aConnectorInAnotherOrganizationSeesNoVariables() {
        var connectorId = connector();
        adminService.create(connectorId, orgId, constant("v", "x"));

        assertThat(lookupService.summariesForConnector(connectorId, UUID.randomUUID())).isEmpty();
    }
}
