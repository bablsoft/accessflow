package com.bablsoft.accessflow.core.internal.secrets;

import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * AWS Secrets Manager store (AF-448). {@code GetSecretValue} on the reference's
 * {@code SecretId} (plain name or full ARN); without a {@code #jsonField} the whole
 * {@code SecretString} is the value, with one the string is parsed as a JSON object and the
 * field extracted. Binary-only secrets are rejected. Credential refresh is owned by the SDK's
 * credentials provider chain.
 */
class AwsSecretsManagerSecretStore implements SecretStore {

    private final SecretsManagerClient client;
    private final ObjectMapper objectMapper;

    AwsSecretsManagerSecretStore(SecretsManagerClient client, ObjectMapper objectMapper) {
        this.client = client;
        this.objectMapper = objectMapper;
    }

    @Override
    public String providerId() {
        return SecretReference.PROVIDER_AWS;
    }

    @Override
    public String fetch(SecretReference reference) {
        String secretString;
        try {
            secretString = client
                    .getSecretValue(GetSecretValueRequest.builder().secretId(reference.path()).build())
                    .secretString();
        } catch (RuntimeException ex) {
            throw new SecretStoreFetchException(
                    "AWS Secrets Manager read failed for " + reference.path(), ex);
        }
        if (secretString == null) {
            throw new SecretStoreFetchException(
                    "AWS secret " + reference.path() + " has no SecretString (binary secrets are not supported)");
        }
        if (reference.field() == null) {
            return secretString;
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(secretString);
        } catch (RuntimeException ex) {
            throw new SecretStoreFetchException(
                    "AWS secret " + reference.path() + " is not a JSON object; cannot extract field '"
                            + reference.field() + "'", ex);
        }
        JsonNode field = root.get(reference.field());
        if (field == null || !field.isValueNode() || field.isNull()) {
            throw new SecretStoreFetchException(
                    "AWS secret field '" + reference.field() + "' missing in " + reference.path());
        }
        return field.asString();
    }
}
