package com.bablsoft.accessflow.audit.internal.sink;

import com.bablsoft.accessflow.audit.internal.codec.S3ObjectLockSinkConfig;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.regions.Region;

import java.net.URI;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class S3ClientFactoryTest {

    private final S3ClientFactory factory = new S3ClientFactory();

    private S3ObjectLockSinkConfig config(String endpoint) {
        return new S3ObjectLockSinkConfig("bucket", "eu-west-1", "audit/", "AKIA123",
                "secret", endpoint, S3ObjectLockSinkConfig.RetentionMode.COMPLIANCE, 30,
                Duration.ofMinutes(15));
    }

    @Test
    void buildsClientWithEndpointOverrideAndPathStyle() {
        try (var client = factory.create(config(" http://localhost:9000 "))) {
            var configuration = client.serviceClientConfiguration();
            assertThat(configuration.region()).isEqualTo(Region.of("eu-west-1"));
            assertThat(configuration.endpointOverride())
                    .contains(URI.create("http://localhost:9000"));
        }
    }

    @Test
    void buildsClientWithoutEndpointOverride() {
        try (var client = factory.create(config(null))) {
            assertThat(client.serviceClientConfiguration().endpointOverride()).isEmpty();
        }
    }

    @Test
    void blankEndpointBehavesLikeAbsent() {
        try (var client = factory.create(config("  "))) {
            assertThat(client.serviceClientConfiguration().endpointOverride()).isEmpty();
        }
    }
}
