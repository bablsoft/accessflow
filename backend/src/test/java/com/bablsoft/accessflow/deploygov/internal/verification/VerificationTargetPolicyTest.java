package com.bablsoft.accessflow.deploygov.internal.verification;

import com.bablsoft.accessflow.deploygov.internal.config.DeploygovProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class VerificationTargetPolicyTest {

    private static final Map<String, String> DNS = Map.of(
            "prom.internal", "10.0.0.5",
            "svc.example.com", "203.0.113.10",
            "api.svc.example.com", "203.0.113.11",
            "rebound.example.com", "127.0.0.1",
            "metadata.example.com", "169.254.169.254",
            "multi.example.com", "203.0.113.12,169.254.10.1");

    private static InetAddress[] resolve(String host) throws UnknownHostException {
        var mapped = DNS.get(host);
        if (mapped == null) {
            // Literals resolve to themselves; unknown names fail like a real lookup.
            if (host.matches("[0-9.]+") || host.contains(":")) {
                return new InetAddress[] {InetAddress.getByName(host)};
            }
            throw new UnknownHostException(host);
        }
        var parts = mapped.split(",");
        var addresses = new InetAddress[parts.length];
        for (int i = 0; i < parts.length; i++) {
            addresses[i] = InetAddress.getByName(parts[i]);
        }
        return addresses;
    }

    private static VerificationTargetPolicy policy(List<String> allowedHosts) {
        var properties = new DeploygovProperties(null, null, null, null, null, allowedHosts);
        return new VerificationTargetPolicy(properties, VerificationTargetPolicyTest::resolve);
    }

    private static String check(String url) {
        return policy(List.of()).check(URI.create(url)).orElse(null);
    }

    @Test
    void allowsPublicAndPrivateTargetsByDefault() {
        assertThat(check("https://svc.example.com/healthz")).isNull();
        assertThat(check("http://prom.internal:9090")).isNull();
        assertThat(check("http://192.168.1.20/healthz")).isNull();
        assertThat(check("http://172.16.0.1/healthz")).isNull();
        assertThat(check("http://100.100.100.201/healthz")).isNull();
        assertThat(check("http://[fd20:ce::253]/healthz")).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"ftp://svc.example.com/x", "file:///etc/passwd", "gopher://svc.example.com"})
    void refusesNonHttpSchemes(String url) {
        assertThat(check(url)).isEqualTo(VerificationTargetPolicy.SCHEME_NOT_ALLOWED);
    }

    @Test
    void refusesARelativeUriAsSchemeNotAllowed() {
        assertThat(check("/healthz")).isEqualTo(VerificationTargetPolicy.SCHEME_NOT_ALLOWED);
    }

    @Test
    void refusesUserinfo() {
        assertThat(check("https://user:secret@svc.example.com/healthz"))
                .isEqualTo(VerificationTargetPolicy.USERINFO_PRESENT);
    }

    @Test
    void refusesAMissingHost() {
        assertThat(check("http:///healthz")).isEqualTo(VerificationTargetPolicy.HOST_MISSING);
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://127.0.0.1/healthz", "http://127.8.9.10/healthz", "http://[::1]/healthz",
            "http://0.0.0.0/healthz", "http://[::]/healthz", "http://169.254.10.1/healthz",
            "http://[fe80::1]/healthz", "http://[::ffff:127.0.0.1]/healthz", "http://[::127.0.0.1]/healthz",
            "http://[64:ff9b::7f00:1]/healthz", "http://[2002:7f00:1::]/healthz",
            "http://localhost:8080/healthz", "http://api.localhost/healthz", "http://LOCALHOST./healthz"})
    void refusesLoopbackUnspecifiedAndLinkLocal(String url) {
        assertThat(check(url)).isEqualTo(VerificationTargetPolicy.RESTRICTED_ADDRESS);
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://169.254.169.254/latest/meta-data", "http://[fd00:ec2::254]/latest",
            "http://metadata.google.internal/computeMetadata/v1", "http://metadata.google.internal./x",
            "http://metadata.example.com/x", "http://[::ffff:169.254.169.254]/x",
            "http://100.100.100.200/latest/meta-data", "http://[fd20:ce::254]/computeMetadata/v1"})
    void refusesCloudMetadataEndpoints(String url) {
        assertThat(check(url)).isEqualTo(VerificationTargetPolicy.METADATA_ENDPOINT);
    }

    @Test
    void refusesANameThatResolvesToLoopback() {
        assertThat(check("https://rebound.example.com/healthz"))
                .isEqualTo(VerificationTargetPolicy.RESTRICTED_ADDRESS);
    }

    @Test
    void refusesWhenAnyResolvedAddressIsRestricted() {
        assertThat(check("https://multi.example.com/healthz"))
                .isEqualTo(VerificationTargetPolicy.RESTRICTED_ADDRESS);
    }

    @Test
    void refusesAHostThatDoesNotResolve() {
        assertThat(check("https://typo.example.com/healthz"))
                .isEqualTo(VerificationTargetPolicy.HOST_UNRESOLVABLE);
    }

    @Test
    void refusesAnEmptyResolution() {
        var policy = new VerificationTargetPolicy(
                new DeploygovProperties(null, null, null, null, null, null), host -> new InetAddress[0]);
        assertThat(policy.check(URI.create("https://svc.example.com")))
                .contains(VerificationTargetPolicy.HOST_UNRESOLVABLE);
    }

    @Test
    void allowListMatchesExactlyAndBySubdomainIgnoringCase() {
        var policy = policy(List.of(" SVC.example.com ", ".prom.internal", ""));

        assertThat(policy.check(URI.create("https://svc.example.com/healthz"))).isEmpty();
        assertThat(policy.check(URI.create("https://API.svc.example.com/healthz"))).isEmpty();
        assertThat(policy.check(URI.create("http://prom.internal:9090"))).isEmpty();
        assertThat(policy.check(URI.create("https://evilsvc.example.com/healthz")))
                .contains(VerificationTargetPolicy.HOST_NOT_ALLOWED);
        assertThat(policy.check(URI.create("https://203.0.113.10/healthz")))
                .contains(VerificationTargetPolicy.HOST_NOT_ALLOWED);
    }

    @Test
    void allowListDoesNotExemptARestrictedResolution() {
        var policy = policy(List.of("example.com"));

        assertThat(policy.check(URI.create("https://rebound.example.com/healthz")))
                .contains(VerificationTargetPolicy.RESTRICTED_ADDRESS);
    }

    @Test
    void publicConstructorUsesTheRealResolver() {
        var policy = new VerificationTargetPolicy(new DeploygovProperties(null, null, null, null, null, null));

        assertThat(policy.check(URI.create("http://127.0.0.1/healthz")))
                .contains(VerificationTargetPolicy.RESTRICTED_ADDRESS);
        assertThat(policy.check(URI.create("http://203.0.113.10/healthz"))).isEmpty();
    }
}
