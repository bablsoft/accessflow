package com.bablsoft.accessflow.apigov.internal;

import com.bablsoft.accessflow.apigov.api.ApiConnectorNotFoundException;
import com.bablsoft.accessflow.apigov.api.ApiConnectorVariableNotFoundException;
import com.bablsoft.accessflow.apigov.api.ApiVariableAlgorithm;
import com.bablsoft.accessflow.apigov.api.ApiVariableEncoding;
import com.bablsoft.accessflow.apigov.api.ApiVariableKind;
import com.bablsoft.accessflow.apigov.api.CreateApiConnectorVariableCommand;
import com.bablsoft.accessflow.apigov.api.IllegalApiConnectorVariableException;
import com.bablsoft.accessflow.apigov.api.ReorderApiConnectorVariablesCommand;
import com.bablsoft.accessflow.apigov.api.UpdateApiConnectorVariableCommand;
import com.bablsoft.accessflow.apigov.internal.persistence.entity.ApiConnectorEntity;
import com.bablsoft.accessflow.apigov.internal.persistence.entity.ApiConnectorVariableEntity;
import com.bablsoft.accessflow.apigov.internal.persistence.repo.ApiConnectorRepository;
import com.bablsoft.accessflow.apigov.internal.persistence.repo.ApiConnectorVariableRepository;
import com.bablsoft.accessflow.core.api.CredentialEncryptionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.support.StaticMessageSource;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DefaultApiConnectorVariableAdminServiceTest {

    @Mock private ApiConnectorVariableRepository variableRepository;
    @Mock private ApiConnectorRepository connectorRepository;
    @Mock private CredentialEncryptionService encryptionService;

    private DefaultApiConnectorVariableAdminService service;

    private final UUID orgId = UUID.randomUUID();
    private final UUID connectorId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        var messages = new StaticMessageSource();
        messages.setUseCodeAsDefaultMessage(true);
        service = new DefaultApiConnectorVariableAdminService(variableRepository, connectorRepository,
                encryptionService, messages);

        var connector = new ApiConnectorEntity();
        connector.setId(connectorId);
        connector.setOrganizationId(orgId);
        when(connectorRepository.findByIdAndOrganizationId(connectorId, orgId))
                .thenReturn(Optional.of(connector));
        when(encryptionService.encrypt(anyString())).thenAnswer(inv -> "enc:" + inv.getArgument(0));
        when(variableRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        existing();
    }

    private void existing(ApiConnectorVariableEntity... entities) {
        when(variableRepository
                .findAllByOrganizationIdAndConnectorIdOrderBySortOrderAscCreatedAtAscIdAsc(orgId, connectorId))
                .thenReturn(List.of(entities));
    }

    private ApiConnectorVariableEntity stored(String name, ApiVariableKind kind, String expression) {
        var e = new ApiConnectorVariableEntity();
        e.setId(UUID.randomUUID());
        e.setOrganizationId(orgId);
        e.setConnectorId(connectorId);
        e.setName(name);
        e.setKind(kind);
        e.setExpression(expression);
        when(variableRepository.findByIdAndOrganizationIdAndConnectorId(e.getId(), orgId, connectorId))
                .thenReturn(Optional.of(e));
        return e;
    }

    private static CreateApiConnectorVariableCommand create(String name, ApiVariableKind kind,
                                                            String expression) {
        return new CreateApiConnectorVariableCommand(name, kind, expression, null, null, null, null,
                null, null, null);
    }

    @Nested
    class Scoping {

        @Test
        void treatsAConnectorInAnotherOrganizationAsNotFound() {
            when(connectorRepository.findByIdAndOrganizationId(any(), any())).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.listForConnector(connectorId, UUID.randomUUID()))
                    .isInstanceOf(ApiConnectorNotFoundException.class);
        }

        @Test
        void throwsWhenTheVariableIsNotOnThisConnector() {
            when(variableRepository.findByIdAndOrganizationIdAndConnectorId(any(), any(), any()))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.delete(UUID.randomUUID(), connectorId, orgId))
                    .isInstanceOf(ApiConnectorVariableNotFoundException.class);
        }
    }

    @Nested
    class Naming {

        @Test
        void rejectsABlankName() {
            assertThatThrownBy(() -> service.create(connectorId, orgId,
                    create("  ", ApiVariableKind.CONSTANT, "x")))
                    .isInstanceOf(IllegalApiConnectorVariableException.class)
                    .hasMessageContaining("name_required");
        }

        @Test
        void rejectsANameThatCouldShadowANamespace() {
            assertThatThrownBy(() -> service.create(connectorId, orgId,
                    create("request.body", ApiVariableKind.CONSTANT, "x")))
                    .isInstanceOf(IllegalApiConnectorVariableException.class)
                    .hasMessageContaining("name_invalid");
        }

        @Test
        void rejectsADuplicateName() {
            existing(stored("sig", ApiVariableKind.CONSTANT, "x"));

            assertThatThrownBy(() -> service.create(connectorId, orgId,
                    create("sig", ApiVariableKind.CONSTANT, "y")))
                    .isInstanceOf(IllegalApiConnectorVariableException.class)
                    .hasMessageContaining("name_duplicate");
        }

        @Test
        void trimsTheName() {
            var view = service.create(connectorId, orgId, create(" sig ", ApiVariableKind.CONSTANT, "x"));

            assertThat(view.name()).isEqualTo("sig");
        }

        @Test
        void rejectsAMissingKind() {
            assertThatThrownBy(() -> service.create(connectorId, orgId, create("v", null, "x")))
                    .isInstanceOf(IllegalApiConnectorVariableException.class)
                    .hasMessageContaining("kind_required");
        }
    }

    @Nested
    class KindFieldRules {

        @Test
        void requiresAnExpressionForConstant() {
            assertThatThrownBy(() -> service.create(connectorId, orgId,
                    create("v", ApiVariableKind.CONSTANT, null)))
                    .isInstanceOf(IllegalApiConnectorVariableException.class)
                    .hasMessageContaining("expression_required");
        }

        @Test
        void forbidsAnExpressionForUuid() {
            assertThatThrownBy(() -> service.create(connectorId, orgId,
                    create("v", ApiVariableKind.UUID, "anything")))
                    .isInstanceOf(IllegalApiConnectorVariableException.class)
                    .hasMessageContaining("expression_forbidden");
        }

        @Test
        void allowsABlankExpressionForTimestamp() {
            assertThat(service.create(connectorId, orgId, create("ts", ApiVariableKind.TIMESTAMP, null))
                    .kind()).isEqualTo(ApiVariableKind.TIMESTAMP);
        }

        @Test
        void requiresADigestAlgorithmForHash() {
            assertThatThrownBy(() -> service.create(connectorId, orgId,
                    create("h", ApiVariableKind.HASH, "x")))
                    .isInstanceOf(IllegalApiConnectorVariableException.class)
                    .hasMessageContaining("algorithm_invalid");
        }

        /** An HMAC algorithm on a HASH — or a digest on an HMAC — is always a configuration error. */
        @Test
        void rejectsAnAlgorithmThatDoesNotMatchTheKind() {
            var hashWithMac = new CreateApiConnectorVariableCommand("h", ApiVariableKind.HASH, "x",
                    ApiVariableAlgorithm.HMAC_SHA256, null, null, null, null, null, null);

            assertThatThrownBy(() -> service.create(connectorId, orgId, hashWithMac))
                    .isInstanceOf(IllegalApiConnectorVariableException.class)
                    .hasMessageContaining("algorithm_invalid");
        }

        @Test
        void forbidsAnAlgorithmOnAKindThatHasNone() {
            var constantWithAlgorithm = new CreateApiConnectorVariableCommand("v",
                    ApiVariableKind.CONSTANT, "x", ApiVariableAlgorithm.SHA256, null, null, null,
                    null, null, null);

            assertThatThrownBy(() -> service.create(connectorId, orgId, constantWithAlgorithm))
                    .isInstanceOf(IllegalApiConnectorVariableException.class)
                    .hasMessageContaining("algorithm_forbidden");
        }

        @Test
        void requiresAnEncodingForEncode() {
            assertThatThrownBy(() -> service.create(connectorId, orgId,
                    create("e", ApiVariableKind.ENCODE, "x")))
                    .isInstanceOf(IllegalApiConnectorVariableException.class)
                    .hasMessageContaining("encoding_required");
        }

        /** CONSTANT ignores encoding by design; accepting one would be silently ineffective. */
        @Test
        void forbidsAnEncodingOnConstant() {
            var constantWithEncoding = new CreateApiConnectorVariableCommand("v",
                    ApiVariableKind.CONSTANT, "x", null, ApiVariableEncoding.BASE64, null, null,
                    null, null, null);

            assertThatThrownBy(() -> service.create(connectorId, orgId, constantWithEncoding))
                    .isInstanceOf(IllegalApiConnectorVariableException.class)
                    .hasMessageContaining("encoding_forbidden");
        }

        @Test
        void requiresASecretForHmac() {
            var hmacNoSecret = new CreateApiConnectorVariableCommand("sig", ApiVariableKind.HMAC, "x",
                    ApiVariableAlgorithm.HMAC_SHA256, null, null, null, null, null, null);

            assertThatThrownBy(() -> service.create(connectorId, orgId, hmacNoSecret))
                    .isInstanceOf(IllegalApiConnectorVariableException.class)
                    .hasMessageContaining("secret_required");
        }

        @Test
        void forbidsASecretOnANonHmacKind() {
            var constantWithSecret = new CreateApiConnectorVariableCommand("v",
                    ApiVariableKind.CONSTANT, "x", null, null, "k", null, null, null, null);

            assertThatThrownBy(() -> service.create(connectorId, orgId, constantWithSecret))
                    .isInstanceOf(IllegalApiConnectorVariableException.class)
                    .hasMessageContaining("secret_forbidden");
        }

        @Test
        void rejectsAnOutOfRangeRandomHexSize() {
            assertThatThrownBy(() -> service.create(connectorId, orgId,
                    create("n", ApiVariableKind.RANDOM_HEX, "999")))
                    .isInstanceOf(IllegalApiConnectorVariableException.class)
                    .hasMessageContaining("random_hex_size");
            assertThatThrownBy(() -> service.create(connectorId, orgId,
                    create("n", ApiVariableKind.RANDOM_HEX, "nope")))
                    .isInstanceOf(IllegalApiConnectorVariableException.class)
                    .hasMessageContaining("random_hex_size");
        }
    }

    @Nested
    class Secrets {

        private CreateApiConnectorVariableCommand hmac(String secret) {
            return new CreateApiConnectorVariableCommand("sig", ApiVariableKind.HMAC, "{{request.body}}",
                    ApiVariableAlgorithm.HMAC_SHA256, ApiVariableEncoding.HEX, secret, null, null,
                    null, null);
        }

        @Test
        void encryptsTheSecretAndReportsOnlyItsPresence() {
            var view = service.create(connectorId, orgId, hmac("shared-key"));

            assertThat(view.hasSecret()).isTrue();
            verify(encryptionService).encrypt("shared-key");
        }

        @Test
        void leavesTheStoredSecretAloneWhenTheUpdateOmitsIt() {
            var entity = stored("sig", ApiVariableKind.HMAC, "{{request.body}}");
            entity.setAlgorithm(ApiVariableAlgorithm.HMAC_SHA256);
            entity.setSecretEncrypted("enc:old");
            existing(entity);

            service.update(entity.getId(), connectorId, orgId, new UpdateApiConnectorVariableCommand(
                    "sig", ApiVariableKind.HMAC, "{{request.body}}", ApiVariableAlgorithm.HMAC_SHA256,
                    null, null, null, null, null, null, null));

            assertThat(entity.getSecretEncrypted()).isEqualTo("enc:old");
        }

        @Test
        void replacesTheSecretWhenOneIsSupplied() {
            var entity = stored("sig", ApiVariableKind.HMAC, "{{request.body}}");
            entity.setAlgorithm(ApiVariableAlgorithm.HMAC_SHA256);
            entity.setSecretEncrypted("enc:old");
            existing(entity);

            service.update(entity.getId(), connectorId, orgId, new UpdateApiConnectorVariableCommand(
                    "sig", ApiVariableKind.HMAC, "{{request.body}}", ApiVariableAlgorithm.HMAC_SHA256,
                    null, "new-key", null, null, null, null, null));

            assertThat(entity.getSecretEncrypted()).isEqualTo("enc:new-key");
        }

        @Test
        void clearingTheSecretOfAnHmacIsRejectedBecauseItWouldBeUnresolvable() {
            var entity = stored("sig", ApiVariableKind.HMAC, "{{request.body}}");
            entity.setAlgorithm(ApiVariableAlgorithm.HMAC_SHA256);
            entity.setSecretEncrypted("enc:old");
            existing(entity);

            assertThatThrownBy(() -> service.update(entity.getId(), connectorId, orgId,
                    new UpdateApiConnectorVariableCommand("sig", ApiVariableKind.HMAC,
                            "{{request.body}}", ApiVariableAlgorithm.HMAC_SHA256, null, null, true,
                            null, null, null, null)))
                    .isInstanceOf(IllegalApiConnectorVariableException.class)
                    .hasMessageContaining("secret_required");
        }
    }

    @Nested
    class Overridable {

        /** A submitter must never be able to override a value that <em>is</em> a secret. */
        @Test
        void rejectsMarkingAnHmacVariableOverridable() {
            var command = new CreateApiConnectorVariableCommand("sig", ApiVariableKind.HMAC,
                    "{{request.body}}", ApiVariableAlgorithm.HMAC_SHA256, ApiVariableEncoding.HEX,
                    "k", null, true, null, null);

            assertThatThrownBy(() -> service.create(connectorId, orgId, command))
                    .isInstanceOf(IllegalApiConnectorVariableException.class)
                    .hasMessageContaining("overridable_secret");
        }

        @Test
        void allowsMarkingANonSecretVariableOverridable() {
            var command = new CreateApiConnectorVariableCommand("nonce", ApiVariableKind.RANDOM_HEX,
                    null, null, null, null, null, true, null, null);

            assertThat(service.create(connectorId, orgId, command).overridable()).isTrue();
        }

        @Test
        void defaultsToNotOverridable() {
            assertThat(service.create(connectorId, orgId, create("v", ApiVariableKind.CONSTANT, "x"))
                    .overridable()).isFalse();
        }
    }

    @Nested
    class Targets {

        private CreateApiConnectorVariableCommand withTarget(String name, String target) {
            return new CreateApiConnectorVariableCommand(name, ApiVariableKind.EPOCH_MILLIS, null,
                    null, null, null, target, null, null, null);
        }

        @Test
        void acceptsAWellFormedHeaderTarget() {
            assertThat(service.create(connectorId, orgId, withTarget("ts", "header:X-Timestamp"))
                    .target()).isEqualTo("header:X-Timestamp");
        }

        @Test
        void rejectsAMalformedTarget() {
            assertThatThrownBy(() -> service.create(connectorId, orgId, withTarget("ts", "body")))
                    .isInstanceOf(IllegalApiConnectorVariableException.class)
                    .hasMessageContaining("target_invalid");
        }

        @Test
        void rejectsTwoVariablesInjectingIntoTheSameHeader() {
            var other = stored("ts2", ApiVariableKind.EPOCH_MILLIS, null);
            other.setTarget("header:X-Timestamp");
            existing(other);

            assertThatThrownBy(() -> service.create(connectorId, orgId,
                    withTarget("ts", "header:x-timestamp")))
                    .isInstanceOf(IllegalApiConnectorVariableException.class)
                    .hasMessageContaining("target_duplicate");
        }

        @Test
        void allowsTheSameKeyInDifferentTargetTypes() {
            var other = stored("q", ApiVariableKind.EPOCH_MILLIS, null);
            other.setTarget("query:ts");
            existing(other);

            assertThat(service.create(connectorId, orgId, withTarget("h", "header:ts")).target())
                    .isEqualTo("header:ts");
        }
    }

    @Nested
    class Graph {

        @Test
        void rejectsACycleIntroducedByTheCandidate() {
            existing(stored("a", ApiVariableKind.CONSTANT, "{{b}}"));

            assertThatThrownBy(() -> service.create(connectorId, orgId,
                    create("b", ApiVariableKind.CONSTANT, "{{a}}")))
                    .isInstanceOf(IllegalApiConnectorVariableException.class)
                    .hasMessageContaining("cycle");
        }

        @Test
        void rejectsASelfReference() {
            assertThatThrownBy(() -> service.create(connectorId, orgId,
                    create("a", ApiVariableKind.CONSTANT, "{{a}}")))
                    .isInstanceOf(IllegalApiConnectorVariableException.class)
                    .hasMessageContaining("cycle");
        }

        @Test
        void rejectsAQualifiedReferenceToAnUnknownVariable() {
            assertThatThrownBy(() -> service.create(connectorId, orgId,
                    create("a", ApiVariableKind.CONSTANT, "{{var.nope}}")))
                    .isInstanceOf(IllegalApiConnectorVariableException.class)
                    .hasMessageContaining("unknown_reference");
        }

        @Test
        void acceptsABareReferenceToAnUnknownName() {
            // It stays literal at render time, so it is not a dangling dependency.
            assertThat(service.create(connectorId, orgId,
                    create("a", ApiVariableKind.CONSTANT, "{{handlebars}}")).name()).isEqualTo("a");
        }

        @Test
        void rejectsACycleIntroducedByAnUpdate() {
            var a = stored("a", ApiVariableKind.CONSTANT, "{{b}}");
            var b = stored("b", ApiVariableKind.CONSTANT, "plain");
            existing(a, b);

            assertThatThrownBy(() -> service.update(b.getId(), connectorId, orgId,
                    new UpdateApiConnectorVariableCommand("b", ApiVariableKind.CONSTANT, "{{a}}",
                            null, null, null, null, null, null, null, null)))
                    .isInstanceOf(IllegalApiConnectorVariableException.class)
                    .hasMessageContaining("cycle");
        }
    }

    @Nested
    class Deletion {

        @Test
        void deletesAnUnreferencedVariable() {
            var v = stored("v", ApiVariableKind.CONSTANT, "x");
            existing(v);

            service.delete(v.getId(), connectorId, orgId);

            verify(variableRepository).delete(v);
        }

        @Test
        void refusesToDeleteAVariableAnotherOneReferences() {
            var base = stored("base", ApiVariableKind.CONSTANT, "x");
            var dependent = stored("dep", ApiVariableKind.CONSTANT, "{{base}}");
            existing(base, dependent);

            assertThatThrownBy(() -> service.delete(base.getId(), connectorId, orgId))
                    .isInstanceOf(IllegalApiConnectorVariableException.class)
                    .hasMessageContaining("referenced");
            verify(variableRepository, never()).delete(any());
        }

        /** A rename orphans every reference to the old name, so it is guarded like a delete. */
        @Test
        void refusesToRenameAVariableAnotherOneReferences() {
            var base = stored("base", ApiVariableKind.CONSTANT, "x");
            var dependent = stored("dep", ApiVariableKind.CONSTANT, "{{base}}");
            existing(base, dependent);

            assertThatThrownBy(() -> service.update(base.getId(), connectorId, orgId,
                    new UpdateApiConnectorVariableCommand("renamed", ApiVariableKind.CONSTANT, "x",
                            null, null, null, null, null, null, null, null)))
                    .isInstanceOf(IllegalApiConnectorVariableException.class)
                    .hasMessageContaining("referenced");
        }
    }

    @Nested
    class Ordering {

        @Test
        void assignsTheNextSortOrderOnCreate() {
            var first = stored("a", ApiVariableKind.CONSTANT, "x");
            first.setSortOrder(4);
            existing(first);

            assertThat(service.create(connectorId, orgId, create("b", ApiVariableKind.CONSTANT, "y"))
                    .sortOrder()).isEqualTo(5);
        }

        @Test
        void reassignsSortOrderFromTheRequestedSequence() {
            var a = stored("a", ApiVariableKind.CONSTANT, "x");
            var b = stored("b", ApiVariableKind.CONSTANT, "y");
            existing(a, b);

            service.reorder(connectorId, orgId,
                    new ReorderApiConnectorVariablesCommand(List.of(b.getId(), a.getId())));

            assertThat(b.getSortOrder()).isZero();
            assertThat(a.getSortOrder()).isEqualTo(1);
        }

        /** A partial reorder would leave the rest at stale positions — a silent surprise. */
        @Test
        void rejectsAnIncompleteIdList() {
            var a = stored("a", ApiVariableKind.CONSTANT, "x");
            existing(a, stored("b", ApiVariableKind.CONSTANT, "y"));

            assertThatThrownBy(() -> service.reorder(connectorId, orgId,
                    new ReorderApiConnectorVariablesCommand(List.of(a.getId()))))
                    .isInstanceOf(IllegalApiConnectorVariableException.class)
                    .hasMessageContaining("reorder_incomplete");
        }

        @Test
        void rejectsAnIdListContainingAForeignId() {
            var a = stored("a", ApiVariableKind.CONSTANT, "x");
            existing(a);

            assertThatThrownBy(() -> service.reorder(connectorId, orgId,
                    new ReorderApiConnectorVariablesCommand(List.of(UUID.randomUUID()))))
                    .isInstanceOf(IllegalApiConnectorVariableException.class)
                    .hasMessageContaining("reorder_incomplete");
        }
    }

    @Nested
    class Listing {

        @Test
        void mapsStoredRowsToViewsWithoutTheSecret() {
            var v = stored("sig", ApiVariableKind.HMAC, "{{request.body}}");
            v.setAlgorithm(ApiVariableAlgorithm.HMAC_SHA256);
            v.setSecretEncrypted("enc:k");
            v.setDescription("Vendor signature");
            existing(v);

            assertThat(service.listForConnector(connectorId, orgId)).singleElement().satisfies(view -> {
                assertThat(view.name()).isEqualTo("sig");
                assertThat(view.hasSecret()).isTrue();
                assertThat(view.description()).isEqualTo("Vendor signature");
            });
        }
    }
}
