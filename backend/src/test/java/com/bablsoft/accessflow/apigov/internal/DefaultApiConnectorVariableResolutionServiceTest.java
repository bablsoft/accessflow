package com.bablsoft.accessflow.apigov.internal;

import com.bablsoft.accessflow.apigov.api.ApiExecutionException;
import com.bablsoft.accessflow.apigov.api.ApiVariableAlgorithm;
import com.bablsoft.accessflow.apigov.api.ApiVariableEncoding;
import com.bablsoft.accessflow.apigov.api.ApiVariableKind;
import com.bablsoft.accessflow.apigov.api.ApiVariableRequestContext;
import com.bablsoft.accessflow.apigov.api.ApiVariableTargetType;
import com.bablsoft.accessflow.apigov.internal.config.ApigovRequestProperties;
import com.bablsoft.accessflow.apigov.internal.persistence.entity.ApiConnectorVariableEntity;
import com.bablsoft.accessflow.apigov.internal.persistence.repo.ApiConnectorVariableRepository;
import com.bablsoft.accessflow.core.api.CredentialEncryptionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.support.StaticMessageSource;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DefaultApiConnectorVariableResolutionServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-20T10:30:45Z");

    @Mock private ApiConnectorVariableRepository variableRepository;
    @Mock private CredentialEncryptionService encryptionService;

    private DefaultApiConnectorVariableResolutionService service;

    private final UUID orgId = UUID.randomUUID();
    private final UUID connectorId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        var messages = new StaticMessageSource();
        messages.setUseCodeAsDefaultMessage(true);
        service = new DefaultApiConnectorVariableResolutionService(variableRepository,
                encryptionService, new ApiVariableEvaluator(Clock.fixed(NOW, ZoneOffset.UTC)),
                new ApigovRequestProperties(1L, 1L, 1L, 8192), messages);
        // The stored secret is AES-encrypted; the test double is an identity transform.
        when(encryptionService.decrypt(anyString())).thenAnswer(inv -> inv.getArgument(0));
    }

    private ApiConnectorVariableEntity variable(String name, ApiVariableKind kind, String expression) {
        var e = new ApiConnectorVariableEntity();
        e.setId(UUID.randomUUID());
        e.setOrganizationId(orgId);
        e.setConnectorId(connectorId);
        e.setName(name);
        e.setKind(kind);
        e.setExpression(expression);
        return e;
    }

    private void stored(ApiConnectorVariableEntity... entities) {
        when(variableRepository
                .findAllByOrganizationIdAndConnectorIdOrderBySortOrderAscCreatedAtAscIdAsc(any(), any()))
                .thenReturn(List.of(entities));
    }

    private ApiVariableRequestContext context(String body, Map<String, String> headers) {
        return new ApiVariableRequestContext("POST", "/v1/pay", "a=1", body, headers);
    }

    @Test
    void returnsEmptyWhenTheConnectorHasNoVariables() {
        stored();

        var resolved = service.resolve(orgId, connectorId, context("", Map.of()), Map.of());

        assertThat(resolved.isEmpty()).isTrue();
    }

    /**
     * The motivating vendor scheme, end to end: the signature covers the resolved Authorization
     * header concatenated with the request body, where the body still carries the literal
     * {@code {{signature}}} placeholder. The digest is substituted back in afterwards, which is why
     * the request context must describe the request <em>before</em> substitution.
     */
    @Test
    void computesTheVendorHmacOverTheAuthHeaderAndThePlaceholderBody() throws Exception {
        var signature = variable("signature", ApiVariableKind.HMAC,
                "{{request.headers.Authorization}}{{request.body}}");
        signature.setAlgorithm(ApiVariableAlgorithm.HMAC_SHA256);
        signature.setEncoding(ApiVariableEncoding.HEX);
        signature.setSecretEncrypted("shared-key");
        stored(signature);

        var body = "{\"data\":\"example_data\",\"HMAC\":\"{{signature}}\"}";
        var authHeader = "Basic token_value";

        var resolved = service.resolve(orgId, connectorId,
                context(body, Map.of("Authorization", authHeader)), Map.of());

        var mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec("shared-key".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        var expected = HexFormat.of()
                .formatHex(mac.doFinal((authHeader + body).getBytes(StandardCharsets.UTF_8)));

        assertThat(resolved.values()).containsEntry("signature", expected);
    }

    @Test
    void matchesRequestHeaderNamesCaseInsensitively() {
        var v = variable("echo", ApiVariableKind.CONSTANT, "{{request.headers.authorization}}");
        stored(v);

        var resolved = service.resolve(orgId, connectorId,
                context("", Map.of("Authorization", "Bearer t")), Map.of());

        assertThat(resolved.values()).containsEntry("echo", "Bearer t");
    }

    @Test
    void exposesEveryRequestContextField() {
        var v = variable("all", ApiVariableKind.CONSTANT,
                "{{request.method}}|{{request.path}}|{{request.query}}|{{request.body}}");
        stored(v);

        var resolved = service.resolve(orgId, connectorId, context("BODY", Map.of()), Map.of());

        assertThat(resolved.values()).containsEntry("all", "POST|/v1/pay|a=1|BODY");
    }

    @Test
    void resolvesVariablesInDependencyOrder() {
        var nonce = variable("nonce", ApiVariableKind.CONSTANT, "N1");
        var wrapped = variable("wrapped", ApiVariableKind.CONSTANT, "[{{nonce}}]");
        wrapped.setSortOrder(0);
        nonce.setSortOrder(1);
        stored(wrapped, nonce);

        var resolved = service.resolve(orgId, connectorId, context("", Map.of()), Map.of());

        assertThat(resolved.values()).containsEntry("wrapped", "[N1]");
    }

    @Test
    void buildsInjectionsFromTargets() {
        var v = variable("ts", ApiVariableKind.EPOCH_MILLIS, null);
        v.setTarget("header:X-Timestamp");
        stored(v);

        var resolved = service.resolve(orgId, connectorId, context("", Map.of()), Map.of());

        assertThat(resolved.injections()).singleElement().satisfies(i -> {
            assertThat(i.type()).isEqualTo(ApiVariableTargetType.HEADER);
            assertThat(i.key()).isEqualTo("X-Timestamp");
            assertThat(i.value()).isEqualTo(Long.toString(NOW.toEpochMilli()));
        });
    }

    @Test
    void emitsNoInjectionForAVariableWithoutATarget() {
        stored(variable("v", ApiVariableKind.CONSTANT, "x"));

        assertThat(service.resolve(orgId, connectorId, context("", Map.of()), Map.of()).injections())
                .isEmpty();
    }

    @Test
    void failsWhenAnHmacVariableHasNoSecret() {
        var v = variable("sig", ApiVariableKind.HMAC, "data");
        v.setAlgorithm(ApiVariableAlgorithm.HMAC_SHA256);
        stored(v);

        assertThatThrownBy(() -> service.resolve(orgId, connectorId, context("", Map.of()), Map.of()))
                .isInstanceOf(ApiExecutionException.class)
                .hasMessageContaining("error.api_connector_variable_secret_required");
    }

    @Test
    void failsOnACycleThatSurvivedSaveTimeValidation() {
        stored(variable("a", ApiVariableKind.CONSTANT, "{{b}}"),
                variable("b", ApiVariableKind.CONSTANT, "{{a}}"));

        assertThatThrownBy(() -> service.resolve(orgId, connectorId, context("", Map.of()), Map.of()))
                .isInstanceOf(ApiExecutionException.class)
                .hasMessageContaining("error.api_connector_variable_cycle");
    }

    @Test
    void failsOnAQualifiedReferenceToAnUnknownVariable() {
        stored(variable("a", ApiVariableKind.CONSTANT, "{{var.gone}}"));

        assertThatThrownBy(() -> service.resolve(orgId, connectorId, context("", Map.of()), Map.of()))
                .isInstanceOf(ApiExecutionException.class)
                .hasMessageContaining("error.api_connector_variable_unknown_reference");
    }

    @Test
    void failsWhenAResolvedValueExceedsTheSizeCap() {
        var messages = new StaticMessageSource();
        messages.setUseCodeAsDefaultMessage(true);
        var tiny = new DefaultApiConnectorVariableResolutionService(variableRepository,
                encryptionService, new ApiVariableEvaluator(Clock.fixed(NOW, ZoneOffset.UTC)),
                new ApigovRequestProperties(1L, 1L, 1L, 4), messages);
        stored(variable("v", ApiVariableKind.CONSTANT, "far too long"));

        assertThatThrownBy(() -> tiny.resolve(orgId, connectorId, context("", Map.of()), Map.of()))
                .isInstanceOf(ApiExecutionException.class)
                .hasMessageContaining("error.api_connector_variable_value_too_large");
    }

    /** CR / LF / NUL in a value bound for a header is request splitting. */
    @Test
    void failsWhenAResolvedValueCarriesControlCharacters() {
        stored(variable("v", ApiVariableKind.CONSTANT, "{{request.body}}"));

        assertThatThrownBy(() -> service.resolve(orgId, connectorId,
                context("a\r\nX-Evil: 1", Map.of()), Map.of()))
                .isInstanceOf(ApiExecutionException.class)
                .hasMessageContaining("error.api_connector_variable_value_invalid");
    }

    @Test
    void detectsEachControlCharacter() {
        assertThat(DefaultApiConnectorVariableResolutionService.containsControlCharacters("a\rb")).isTrue();
        assertThat(DefaultApiConnectorVariableResolutionService.containsControlCharacters("a\nb")).isTrue();
        assertThat(DefaultApiConnectorVariableResolutionService.containsControlCharacters("a\0b")).isTrue();
        assertThat(DefaultApiConnectorVariableResolutionService.containsControlCharacters("ab")).isFalse();
    }

    @Test
    void appliesAnOverrideToAnOverridableVariable() {
        var v = variable("nonce", ApiVariableKind.RANDOM_HEX, null);
        v.setOverridable(true);
        stored(v);

        var resolved = service.resolve(orgId, connectorId, context("", Map.of()),
                Map.of("nonce", "fixed-nonce"));

        assertThat(resolved.values()).containsEntry("nonce", "fixed-nonce");
    }

    /** An override replaces the value outright; the kind is not re-applied over it. */
    @Test
    void anOverrideReplacesTheValueWithoutReapplyingTheKind() {
        var v = variable("id", ApiVariableKind.UUID, null);
        v.setOverridable(true);
        stored(v);

        var resolved = service.resolve(orgId, connectorId, context("", Map.of()), Map.of("id", "abc"));

        assertThat(resolved.values()).containsEntry("id", "abc");
    }

    @Test
    void ignoresAnOverrideTargetingANonOverridableVariable() {
        // The submit path already rejects these; the resolver is belt-and-braces.
        stored(variable("locked", ApiVariableKind.CONSTANT, "real"));

        var resolved = service.resolve(orgId, connectorId, context("", Map.of()),
                Map.of("locked", "spoofed"));

        assertThat(resolved.values()).containsEntry("locked", "real");
    }

    @Test
    void dependentsRecomputeOverAnOverriddenValue() {
        var nonce = variable("nonce", ApiVariableKind.CONSTANT, "generated");
        nonce.setOverridable(true);
        var wrapper = variable("wrapper", ApiVariableKind.CONSTANT, "[{{nonce}}]");
        stored(nonce, wrapper);

        var resolved = service.resolve(orgId, connectorId, context("", Map.of()),
                Map.of("nonce", "supplied"));

        assertThat(resolved.values()).containsEntry("wrapper", "[supplied]");
    }

    /**
     * The containment property behind per-request overrides: an override is an opaque literal, never
     * re-rendered, so it cannot expand into another variable's (possibly secret-derived) value.
     */
    @Test
    void anOverrideIsNeverExpandedAsATemplate() {
        var secret = variable("signingKey", ApiVariableKind.CONSTANT, "s3cr3t");
        var open = variable("label", ApiVariableKind.CONSTANT, "plain");
        open.setOverridable(true);
        stored(secret, open);

        var resolved = service.resolve(orgId, connectorId, context("", Map.of()),
                Map.of("label", "{{signingKey}}"));

        assertThat(resolved.values()).containsEntry("label", "{{signingKey}}");
    }

    @Test
    void summariesCarryNoExpressionAlgorithmOrSecret() {
        var v = variable("sig", ApiVariableKind.HMAC, "{{request.body}}");
        v.setAlgorithm(ApiVariableAlgorithm.HMAC_SHA256);
        v.setSecretEncrypted("k");
        v.setDescription("Vendor signature");
        stored(v);

        var summaries = service.summariesForConnector(connectorId, orgId);

        assertThat(summaries).singleElement().satisfies(s -> {
            assertThat(s.name()).isEqualTo("sig");
            assertThat(s.kind()).isEqualTo(ApiVariableKind.HMAC);
            assertThat(s.description()).isEqualTo("Vendor signature");
            assertThat(s.overridable()).isFalse();
        });
    }

    @Test
    void overridableNamesListsOnlyTheOptedInVariables() {
        var open = variable("nonce", ApiVariableKind.RANDOM_HEX, null);
        open.setOverridable(true);
        stored(open, variable("locked", ApiVariableKind.CONSTANT, "x"));

        assertThat(service.overridableNames(connectorId, orgId)).containsExactly("nonce");
    }
}
