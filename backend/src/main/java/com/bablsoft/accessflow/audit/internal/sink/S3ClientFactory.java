package com.bablsoft.accessflow.audit.internal.sink;

import com.bablsoft.accessflow.audit.internal.codec.S3ObjectLockSinkConfig;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

import java.net.URI;
import java.time.Duration;

/**
 * Builds a short-lived {@link S3Client} from a sink's decrypted config. Deliberately not a
 * Spring bean per client: credentials/region/endpoint are per-sink row, and the caller closes
 * the client after each delivery. Same no-Netty url-connection transport choice as the AWS
 * Secrets Manager store and the DynamoDB engine. Extracted as a component so tests can seam in
 * a mocked client.
 */
@Component
public class S3ClientFactory {

    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    static final Duration API_CALL_TIMEOUT = Duration.ofSeconds(30);

    public S3Client create(S3ObjectLockSinkConfig config) {
        var httpClient = UrlConnectionHttpClient.builder()
                .connectionTimeout(CONNECT_TIMEOUT)
                .socketTimeout(API_CALL_TIMEOUT)
                .build();
        var builder = S3Client.builder()
                .region(Region.of(config.region()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(config.accessKeyId(),
                                config.secretAccessKeyPlain())))
                .httpClient(httpClient)
                .overrideConfiguration(o -> o.apiCallTimeout(API_CALL_TIMEOUT));
        if (config.endpoint() != null && !config.endpoint().isBlank()) {
            // S3-compatible stores (MinIO, LocalStack) rarely support virtual-host addressing.
            builder.endpointOverride(URI.create(config.endpoint().strip())).forcePathStyle(true);
        }
        return builder.build();
    }
}
