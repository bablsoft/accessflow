package com.bablsoft.accessflow.deploygov.internal.verification;

import com.bablsoft.accessflow.deploygov.internal.config.DeploygovProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The outbound URL policy for post-deployment verification checks (#1024) — the first explicit
 * outbound policy in the codebase, deliberately kept inside {@code deploygov}. It runs at write time
 * and again before every sample, so an allow-list change applies on the next sample.
 *
 * <p>Private (RFC 1918) ranges are allowed: Prometheus is almost always internal. Loopback,
 * unspecified, link-local and the cloud metadata endpoints are always denied, also when embedded in
 * an IPv6 address. The metadata endpoints are {@code 169.254.169.254}, {@code fd00:ec2::254},
 * {@code fd20:ce::254}, Alibaba Cloud's {@code 100.100.100.200} and {@code metadata.google.internal}. A non-empty {@code accessflow.deploygov.verification-allowed-hosts} pins the
 * reachable hosts (exact or subdomain match). A host that does not resolve is refused — fail
 * closed. The residual gap is DNS rebinding between this lookup and the HTTP client's own.
 */
@Component
public class VerificationTargetPolicy {

    static final String SCHEME_NOT_ALLOWED = "scheme_not_allowed";
    static final String USERINFO_PRESENT = "userinfo_present";
    static final String HOST_MISSING = "host_missing";
    static final String HOST_NOT_ALLOWED = "host_not_allowed";
    static final String HOST_UNRESOLVABLE = "host_unresolvable";
    static final String RESTRICTED_ADDRESS = "restricted_address";
    static final String METADATA_ENDPOINT = "metadata_endpoint";

    private static final String GCP_METADATA_HOST = "metadata.google.internal";

    private final List<String> allowedHosts;
    private final HostResolver resolver;

    @Autowired
    public VerificationTargetPolicy(DeploygovProperties properties) {
        this(properties, InetAddress::getAllByName);
    }

    VerificationTargetPolicy(DeploygovProperties properties, HostResolver resolver) {
        this.allowedHosts = properties.verificationAllowedHosts().stream()
                .map(h -> h.trim().toLowerCase(Locale.ROOT))
                .map(h -> h.startsWith(".") ? h.substring(1) : h)
                .filter(h -> !h.isEmpty())
                .toList();
        this.resolver = resolver;
    }

    /** The rejection reason code, or empty when {@code uri} may be fetched. */
    public Optional<String> check(URI uri) {
        var scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            return Optional.of(SCHEME_NOT_ALLOWED);
        }
        if (uri.getRawUserInfo() != null) {
            return Optional.of(USERINFO_PRESENT);
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            return Optional.of(HOST_MISSING);
        }
        var host = stripBrackets(uri.getHost().toLowerCase(Locale.ROOT));
        if (host.endsWith(".")) {
            host = host.substring(0, host.length() - 1);
        }
        if (host.equals(GCP_METADATA_HOST)) {
            return Optional.of(METADATA_ENDPOINT);
        }
        if (host.equals("localhost") || host.endsWith(".localhost")) {
            return Optional.of(RESTRICTED_ADDRESS);
        }
        if (!allowedHosts.isEmpty() && !isAllowListed(host)) {
            return Optional.of(HOST_NOT_ALLOWED);
        }
        InetAddress[] addresses;
        try {
            addresses = resolver.resolve(host);
        } catch (UnknownHostException ex) {
            return Optional.of(HOST_UNRESOLVABLE);
        }
        if (addresses == null || addresses.length == 0) {
            return Optional.of(HOST_UNRESOLVABLE);
        }
        for (var address : addresses) {
            var verdict = classify(address);
            if (verdict.isPresent()) {
                return verdict;
            }
        }
        return Optional.empty();
    }

    private boolean isAllowListed(String host) {
        return allowedHosts.stream().anyMatch(entry -> host.equals(entry) || host.endsWith("." + entry));
    }

    private static String stripBrackets(String host) {
        return host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
    }

    static Optional<String> classify(InetAddress address) {
        var bytes = address.getAddress();
        if (address instanceof Inet4Address) {
            return classifyIpv4(bytes);
        }
        if (isAwsIpv6Metadata(bytes) || isGcpIpv6Metadata(bytes)) {
            return Optional.of(METADATA_ENDPOINT);
        }
        if (address.isLoopbackAddress() || address.isAnyLocalAddress() || address.isLinkLocalAddress()) {
            return Optional.of(RESTRICTED_ADDRESS);
        }
        return embeddedIpv4(bytes).flatMap(VerificationTargetPolicy::classifyIpv4);
    }

    private static Optional<String> classifyIpv4(byte[] b) {
        int first = b[0] & 0xff;
        int second = b[1] & 0xff;
        if ((first == 169 && second == 254 && (b[2] & 0xff) == 169 && (b[3] & 0xff) == 254)
                || (first == 100 && second == 100 && (b[2] & 0xff) == 100 && (b[3] & 0xff) == 200)) {
            return Optional.of(METADATA_ENDPOINT); // AWS/Azure/GCP/OCI, Alibaba Cloud
        }
        if (first == 0 || first == 127 || (first == 169 && second == 254)) {
            return Optional.of(RESTRICTED_ADDRESS);
        }
        return Optional.empty();
    }

    /** {@code fd00:ec2::254} — the AWS IMDS IPv6 endpoint. */
    private static boolean isAwsIpv6Metadata(byte[] b) {
        return (b[0] & 0xff) == 0xfd && b[1] == 0 && (b[2] & 0xff) == 0x0e && (b[3] & 0xff) == 0xc2
                && allZero(b, 4, 14) && b[14] == 0x02 && (b[15] & 0xff) == 0x54;
    }

    /** {@code fd20:ce::254} — the GCP metadata server's IPv6 endpoint. */
    private static boolean isGcpIpv6Metadata(byte[] b) {
        return (b[0] & 0xff) == 0xfd && b[1] == 0x20 && b[2] == 0 && (b[3] & 0xff) == 0xce
                && allZero(b, 4, 14) && b[14] == 0x02 && (b[15] & 0xff) == 0x54;
    }

    /** The IPv4 address an IPv6 address carries: mapped, compatible, NAT64 or 6to4. */
    private static Optional<byte[]> embeddedIpv4(byte[] b) {
        if (allZero(b, 0, 10) && (b[10] & 0xff) == 0xff && (b[11] & 0xff) == 0xff) {
            return Optional.of(Arrays.copyOfRange(b, 12, 16));
        }
        if (allZero(b, 0, 12)) {
            return Optional.of(Arrays.copyOfRange(b, 12, 16));
        }
        if (b[0] == 0x00 && b[1] == 0x64 && (b[2] & 0xff) == 0xff && (b[3] & 0xff) == 0x9b
                && allZero(b, 4, 12)) {
            return Optional.of(Arrays.copyOfRange(b, 12, 16));
        }
        if (b[0] == 0x20 && b[1] == 0x02) {
            return Optional.of(Arrays.copyOfRange(b, 2, 6));
        }
        return Optional.empty();
    }

    private static boolean allZero(byte[] b, int from, int to) {
        for (int i = from; i < to; i++) {
            if (b[i] != 0) {
                return false;
            }
        }
        return true;
    }
}
