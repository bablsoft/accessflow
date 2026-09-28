package com.bablsoft.accessflow.workflow.internal.hook;

import com.bablsoft.accessflow.workflow.api.IllegalDecisionHookException;
import com.bablsoft.accessflow.workflow.internal.config.DecisionHookProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Locale;

/**
 * The SSRF guard for decision hook endpoints (#945). The URL is admin-supplied and called from
 * inside the network, so unless {@code accessflow.workflow.decision-hook.allow-private-network} is
 * on it must be {@code https://} and must not be, or resolve to, an address that reaches the
 * deployment's own network: loopback, unspecified, private (RFC 1918), link-local (cloud metadata
 * included), CGNAT, benchmarking, reserved, multicast, or unique-local IPv6 — also when embedded in
 * an IPv4-mapped, IPv4-compatible, NAT64 or 6to4 IPv6 address.
 *
 * <p>The check runs at save time, for feedback, and again after DNS resolution on every call, so a
 * record changed after the save cannot slip through. The residual gap is DNS rebinding between the
 * call-time lookup and the HTTP client's own; redirects are never followed, so the endpoint cannot
 * bounce the call elsewhere.
 */
@Component
@RequiredArgsConstructor
class DecisionHookUrlGuard {

    static final String KEY_INVALID_URL = "workflow.decision_hook.invalid_url";
    static final String KEY_INSECURE_SCHEME = "workflow.decision_hook.insecure_scheme";
    static final String KEY_RESTRICTED_ADDRESS = "workflow.decision_hook.restricted_address";

    private final DecisionHookProperties properties;

    /**
     * Save-time check. A host that does not resolve now is accepted — the call will fail closed as
     * a transport error — so a DNS blip cannot block an admin from saving.
     *
     * @throws IllegalDecisionHookException carrying the message key of the first rule broken
     */
    URI validate(String endpointUrl) {
        var uri = parse(endpointUrl);
        if (properties.allowPrivateNetwork()) {
            return uri;
        }
        try {
            if (anyRestricted(InetAddress.getAllByName(uri.getHost()))) {
                throw new IllegalDecisionHookException(KEY_RESTRICTED_ADDRESS);
            }
        } catch (UnknownHostException ex) {
            // Accepted on purpose, see above.
        }
        return uri;
    }

    /** Call-time check: whether the resolved endpoint may be called. */
    CallTarget resolve(String endpointUrl) {
        URI uri;
        try {
            uri = parse(endpointUrl);
        } catch (IllegalDecisionHookException ex) {
            return CallTarget.restricted();
        }
        if (properties.allowPrivateNetwork()) {
            return CallTarget.allowed(uri);
        }
        try {
            return anyRestricted(InetAddress.getAllByName(uri.getHost()))
                    ? CallTarget.restricted()
                    : CallTarget.allowed(uri);
        } catch (UnknownHostException ex) {
            return CallTarget.unresolvable();
        }
    }

    private URI parse(String endpointUrl) {
        URI uri;
        try {
            uri = new URI(endpointUrl == null ? "" : endpointUrl.trim());
        } catch (URISyntaxException ex) {
            throw new IllegalDecisionHookException(KEY_INVALID_URL);
        }
        var scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!uri.isAbsolute() || uri.getHost() == null || uri.getHost().isBlank()
                || uri.getRawUserInfo() != null || uri.getRawFragment() != null
                || !(scheme.equals("https") || scheme.equals("http"))) {
            throw new IllegalDecisionHookException(KEY_INVALID_URL);
        }
        if (scheme.equals("http") && !properties.allowPrivateNetwork()) {
            throw new IllegalDecisionHookException(KEY_INSECURE_SCHEME);
        }
        var host = uri.getHost().toLowerCase(Locale.ROOT);
        if (!properties.allowPrivateNetwork()
                && (host.equals("localhost") || host.endsWith(".localhost"))) {
            throw new IllegalDecisionHookException(KEY_RESTRICTED_ADDRESS);
        }
        return uri;
    }

    private boolean anyRestricted(InetAddress[] addresses) {
        return !properties.allowPrivateNetwork()
                && Arrays.stream(addresses).anyMatch(DecisionHookUrlGuard::isRestricted);
    }

    static boolean isRestricted(InetAddress address) {
        if (address.isLoopbackAddress() || address.isAnyLocalAddress()
                || address.isLinkLocalAddress() || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return true;
        }
        var bytes = address.getAddress();
        if (address instanceof Inet4Address) {
            return isRestrictedIpv4(bytes);
        }
        return isRestrictedIpv6(bytes);
    }

    private static boolean isRestrictedIpv4(byte[] b) {
        int first = b[0] & 0xff;
        int second = b[1] & 0xff;
        return first == 0                                     // 0.0.0.0/8
                || first == 10                                 // 10.0.0.0/8
                || first == 127                                // 127.0.0.0/8
                || (first == 100 && second >= 64 && second <= 127) // 100.64.0.0/10 CGNAT
                || (first == 169 && second == 254)             // 169.254.0.0/16 link-local
                || (first == 172 && second >= 16 && second <= 31) // 172.16.0.0/12
                || (first == 192 && second == 0 && (b[2] & 0xff) == 0) // 192.0.0.0/24
                || (first == 192 && second == 168)             // 192.168.0.0/16
                || (first == 198 && (second == 18 || second == 19)) // 198.18.0.0/15
                || first >= 224;                               // multicast, reserved, broadcast
    }

    private static boolean isRestrictedIpv6(byte[] b) {
        int first = b[0] & 0xff;
        if ((first & 0xfe) == 0xfc) {                          // fc00::/7 unique-local
            return true;
        }
        if (allZero(b, 0, 10) && (b[10] & 0xff) == 0xff && (b[11] & 0xff) == 0xff) {
            return isRestrictedIpv4(Arrays.copyOfRange(b, 12, 16)); // ::ffff:a.b.c.d
        }
        if (allZero(b, 0, 12)) {
            return isRestrictedIpv4(Arrays.copyOfRange(b, 12, 16)); // ::a.b.c.d
        }
        if ((b[0] & 0xff) == 0x00 && (b[1] & 0xff) == 0x64 && (b[2] & 0xff) == 0xff
                && (b[3] & 0xff) == 0x9b && allZero(b, 4, 12)) {
            return isRestrictedIpv4(Arrays.copyOfRange(b, 12, 16)); // 64:ff9b::/96 NAT64
        }
        if (first == 0x20 && (b[1] & 0xff) == 0x02) {
            return isRestrictedIpv4(Arrays.copyOfRange(b, 2, 6));   // 2002::/16 6to4
        }
        return false;
    }

    private static boolean allZero(byte[] b, int from, int to) {
        for (int i = from; i < to; i++) {
            if (b[i] != 0) {
                return false;
            }
        }
        return true;
    }

    /** The call-time verdict on an endpoint. */
    record CallTarget(URI uri, boolean blocked) {

        static CallTarget allowed(URI uri) {
            return new CallTarget(uri, false);
        }

        static CallTarget restricted() {
            return new CallTarget(null, true);
        }

        /** The host did not resolve: not an SSRF block, a transport failure. */
        static CallTarget unresolvable() {
            return new CallTarget(null, false);
        }
    }
}
