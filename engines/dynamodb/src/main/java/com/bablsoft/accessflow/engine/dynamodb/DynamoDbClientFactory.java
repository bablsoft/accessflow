package com.bablsoft.accessflow.engine.dynamodb;

import com.bablsoft.accessflow.core.api.CredentialDecryptor;
import com.bablsoft.accessflow.core.api.DatasourceConnectionDescriptor;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

import java.net.URI;

/**
 * Builds a native {@link DynamoDbClient} from a {@link DatasourceConnectionDescriptor} — the single
 * place that maps AccessFlow connection fields onto the AWS SDK. DynamoDB's "connection" is cloud
 * credentials + region, not host/port: {@code database_name} is the AWS region (required for SigV4
 * request signing), {@code username} the access key id, the decrypted {@code password} the secret
 * access key, and {@code jdbc_url_override} an optional custom endpoint (DynamoDB Local / VPC). The
 * HTTP layer is the url-connection client (no Netty). Credentials are decrypted only here, at client
 * construction time, mirroring the host rule that plaintext lives no longer than pool init.
 */
final class DynamoDbClientFactory {

    private final CredentialDecryptor credentials;
    private final DynamoDbEngineSettings settings;

    DynamoDbClientFactory(CredentialDecryptor credentials, DynamoDbEngineSettings settings) {
        this.credentials = credentials;
        this.settings = settings;
    }

    DynamoDbClient open(DatasourceConnectionDescriptor descriptor) {
        var region = Region.of(descriptor.databaseName().strip());
        var awsCredentials = AwsBasicCredentials.create(descriptor.username(),
                credentials.decrypt(descriptor.passwordEncrypted()));
        var httpClient = UrlConnectionHttpClient.builder()
                .connectionTimeout(settings.connectTimeout())
                .build();
        var builder = DynamoDbClient.builder()
                .region(region)
                .credentialsProvider(StaticCredentialsProvider.create(awsCredentials))
                .httpClient(httpClient)
                .overrideConfiguration(o -> o.apiCallTimeout(settings.apiCallTimeout()));
        var endpoint = descriptor.jdbcUrlOverride();
        if (endpoint != null && !endpoint.isBlank()) {
            builder.endpointOverride(URI.create(endpoint.strip()));
        }
        return builder.build();
    }
}
