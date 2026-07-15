package com.bablsoft.accessflow.core.internal.secrets;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueResponse;
import software.amazon.awssdk.services.secretsmanager.model.ResourceNotFoundException;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AwsSecretsManagerSecretStoreTest {

    @Mock
    private SecretsManagerClient client;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private AwsSecretsManagerSecretStore store() {
        return new AwsSecretsManagerSecretStore(client, objectMapper);
    }

    @Test
    void providerIdIsAws() {
        assertThat(store().providerId()).isEqualTo("aws");
    }

    @Test
    void plainSecretStringIsReturnedVerbatim() {
        when(client.getSecretValue(any(GetSecretValueRequest.class)))
                .thenReturn(GetSecretValueResponse.builder().secretString("s3cret").build());

        assertThat(store().fetch(SecretReference.parse("aws:prod/db"))).isEqualTo("s3cret");
    }

    @Test
    void jsonFieldIsExtractedFromJsonSecret() {
        when(client.getSecretValue(any(GetSecretValueRequest.class)))
                .thenReturn(GetSecretValueResponse.builder()
                        .secretString("{\"username\":\"app\",\"password\":\"s3cret\"}").build());

        assertThat(store().fetch(SecretReference.parse("aws:prod/db#password"))).isEqualTo("s3cret");
    }

    @Test
    void secretIdIsPassedThrough() {
        var arn = "arn:aws:secretsmanager:eu-west-1:123456789012:secret:prod/db-AbCdEf";
        when(client.getSecretValue(GetSecretValueRequest.builder().secretId(arn).build()))
                .thenReturn(GetSecretValueResponse.builder().secretString("s3cret").build());

        assertThat(store().fetch(SecretReference.parse("aws:" + arn))).isEqualTo("s3cret");
    }

    @Test
    void binaryOnlySecretThrows() {
        when(client.getSecretValue(any(GetSecretValueRequest.class)))
                .thenReturn(GetSecretValueResponse.builder()
                        .secretBinary(SdkBytes.fromUtf8String("bytes")).build());

        assertThatThrownBy(() -> store().fetch(SecretReference.parse("aws:prod/db")))
                .isInstanceOf(SecretStoreFetchException.class)
                .hasMessageContaining("binary");
    }

    @Test
    void missingJsonFieldThrows() {
        when(client.getSecretValue(any(GetSecretValueRequest.class)))
                .thenReturn(GetSecretValueResponse.builder()
                        .secretString("{\"username\":\"app\"}").build());

        assertThatThrownBy(() -> store().fetch(SecretReference.parse("aws:prod/db#password")))
                .isInstanceOf(SecretStoreFetchException.class)
                .hasMessageContaining("password");
    }

    @Test
    void nonJsonSecretWithFieldThrows() {
        when(client.getSecretValue(any(GetSecretValueRequest.class)))
                .thenReturn(GetSecretValueResponse.builder().secretString("not json").build());

        assertThatThrownBy(() -> store().fetch(SecretReference.parse("aws:prod/db#password")))
                .isInstanceOf(SecretStoreFetchException.class);
    }

    @Test
    void sdkFailureIsWrapped() {
        when(client.getSecretValue(any(GetSecretValueRequest.class)))
                .thenThrow(ResourceNotFoundException.builder().message("gone").build());

        assertThatThrownBy(() -> store().fetch(SecretReference.parse("aws:prod/db")))
                .isInstanceOf(SecretStoreFetchException.class)
                .hasCauseInstanceOf(ResourceNotFoundException.class);
    }
}
