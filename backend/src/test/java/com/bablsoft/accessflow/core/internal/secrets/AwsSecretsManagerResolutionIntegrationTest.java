package com.bablsoft.accessflow.core.internal.secrets;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.localstack.LocalStackContainer;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.CreateSecretRequest;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Live-path proof for AF-448 against a real Secrets Manager API (LocalStack): plain
 * {@code SecretString} fetch, {@code #jsonField} extraction, and missing-secret failure —
 * exercising the same url-connection client + static-credentials + endpoint-override
 * construction that {@link SecretsConfiguration} uses.
 */
class AwsSecretsManagerResolutionIntegrationTest {

    // Pin to the community-edition semver line — the date-tagged images (2026.x) are the
    // licensed Pro build and exit at startup without an auth token.
    private static final LocalStackContainer LOCALSTACK =
            new LocalStackContainer("localstack/localstack:4.9.2")
                    .withServices("secretsmanager");

    private static SecretsManagerClient client;

    @BeforeAll
    static void startLocalStack() {
        LOCALSTACK.start();
        client = SecretsManagerClient.builder()
                .httpClient(UrlConnectionHttpClient.builder().build())
                .endpointOverride(LOCALSTACK.getEndpoint())
                .region(Region.of(LOCALSTACK.getRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(
                        LOCALSTACK.getAccessKey(), LOCALSTACK.getSecretKey())))
                .build();
        client.createSecret(CreateSecretRequest.builder()
                .name("prod/db-plain").secretString("s3cret-from-aws").build());
        client.createSecret(CreateSecretRequest.builder()
                .name("prod/db-json")
                .secretString("{\"username\":\"svc\",\"password\":\"json-s3cret\"}").build());
    }

    @AfterAll
    static void stopLocalStack() {
        if (client != null) {
            client.close();
        }
        LOCALSTACK.stop();
    }

    private AwsSecretsManagerSecretStore store() {
        return new AwsSecretsManagerSecretStore(client, new ObjectMapper());
    }

    @Test
    void fetchesPlainSecretString() {
        assertThat(store().fetch(SecretReference.parse("aws:prod/db-plain")))
                .isEqualTo("s3cret-from-aws");
    }

    @Test
    void extractsJsonFieldFromJsonSecret() {
        assertThat(store().fetch(SecretReference.parse("aws:prod/db-json#password")))
                .isEqualTo("json-s3cret");
    }

    @Test
    void missingSecretFails() {
        assertThatThrownBy(() -> store().fetch(SecretReference.parse("aws:prod/nope")))
                .isInstanceOf(SecretStoreFetchException.class);
    }

    @Test
    void missingJsonFieldFails() {
        assertThatThrownBy(() -> store().fetch(SecretReference.parse("aws:prod/db-json#missing")))
                .isInstanceOf(SecretStoreFetchException.class);
    }
}
