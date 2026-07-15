package com.bablsoft.accessflow.core.internal.secrets;

import com.bablsoft.accessflow.core.api.InvalidSecretReferenceException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SecretReferenceTest {

    @Test
    void detectsReferencePrefixes() {
        assertThat(SecretReference.isReference("vault:secret/prod/db#password")).isTrue();
        assertThat(SecretReference.isReference("aws:my-secret")).isTrue();
        assertThat(SecretReference.isReference("azure:db-password")).isTrue();
    }

    @Test
    void nonReferencesAreNotDetected() {
        assertThat(SecretReference.isReference(null)).isFalse();
        assertThat(SecretReference.isReference("")).isFalse();
        assertThat(SecretReference.isReference("bm90LWEtcmVmZXJlbmNl")).isFalse();
        // Case-sensitive by design — a raw password resembling a prefix keeps working.
        assertThat(SecretReference.isReference("Vault:something")).isFalse();
        assertThat(SecretReference.isReference("AWS:something")).isFalse();
        assertThat(SecretReference.isReference("gcp:unknown-provider")).isFalse();
    }

    @Test
    void parsesVaultReference() {
        var ref = SecretReference.parse("vault:secret/prod/db#password");

        assertThat(ref.provider()).isEqualTo("vault");
        assertThat(ref.path()).isEqualTo("secret/prod/db");
        assertThat(ref.field()).isEqualTo("password");
        assertThat(ref.vaultMount()).isEqualTo("secret");
        assertThat(ref.vaultPath()).isEqualTo("prod/db");
    }

    @Test
    void vaultFieldSplitsOnLastHash() {
        var ref = SecretReference.parse("vault:kv/team#1/db#password");

        assertThat(ref.path()).isEqualTo("kv/team#1/db");
        assertThat(ref.field()).isEqualTo("password");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "vault:secret/db",          // no field
            "vault:secret#password",    // no path under the mount
            "vault:#password",          // no mount
            "vault:secret/db#",         // empty field
            "vault:"                    // empty remainder
    })
    void malformedVaultReferencesThrow(String raw) {
        assertThatThrownBy(() -> SecretReference.parse(raw))
                .isInstanceOf(InvalidSecretReferenceException.class);
    }

    @Test
    void parsesAwsPlainName() {
        var ref = SecretReference.parse("aws:prod/db-credentials");

        assertThat(ref.provider()).isEqualTo("aws");
        assertThat(ref.path()).isEqualTo("prod/db-credentials");
        assertThat(ref.field()).isNull();
    }

    @Test
    void parsesAwsArnWithJsonField() {
        var arn = "arn:aws:secretsmanager:eu-west-1:123456789012:secret:prod/db-AbCdEf";
        var ref = SecretReference.parse("aws:" + arn + "#password");

        assertThat(ref.path()).isEqualTo(arn);
        assertThat(ref.field()).isEqualTo("password");
    }

    @ParameterizedTest
    @ValueSource(strings = {"aws:", "aws:#password", "aws:my-secret#"})
    void malformedAwsReferencesThrow(String raw) {
        assertThatThrownBy(() -> SecretReference.parse(raw))
                .isInstanceOf(InvalidSecretReferenceException.class);
    }

    @Test
    void parsesAzureSecretName() {
        var ref = SecretReference.parse("azure:prod-db-password");

        assertThat(ref.provider()).isEqualTo("azure");
        assertThat(ref.path()).isEqualTo("prod-db-password");
        assertThat(ref.field()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "azure:",                 // empty name
            "azure:with/slash",       // invalid character
            "azure:with_underscore",  // invalid character
            "azure:name#field"        // Key Vault secrets are single values — no field part
    })
    void malformedAzureReferencesThrow(String raw) {
        assertThatThrownBy(() -> SecretReference.parse(raw))
                .isInstanceOf(InvalidSecretReferenceException.class);
    }

    @Test
    void parseOfNonReferenceThrows() {
        assertThatThrownBy(() -> SecretReference.parse("just-a-password"))
                .isInstanceOf(InvalidSecretReferenceException.class)
                .satisfies(ex -> assertThat(((InvalidSecretReferenceException) ex).reference())
                        .isEqualTo("just-a-password"));
    }
}
