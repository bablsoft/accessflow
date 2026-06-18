package com.bablsoft.accessflow.workflow.internal.routing;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;

/**
 * Matches an IP literal against CIDR blocks ({@code address/prefix}), IPv4 and IPv6. Parsing is
 * <strong>literal-only</strong> — an input is validated as a numeric IP shape before any
 * {@link InetAddress} call, so a malformed entry can never trigger a DNS lookup. Used by
 * {@link RoutingConditionEvaluator} for
 * {@link com.bablsoft.accessflow.workflow.api.ConditionNode.SourceIpMatches} and by the routing
 * policy service to validate CIDR syntax at create / update time.
 */
final class CidrMatcher {

    private CidrMatcher() {
    }

    /** @return {@code true} if {@code cidr} is a syntactically valid IPv4 or IPv6 CIDR block. */
    static boolean isValidCidr(String cidr) {
        return parse(cidr) != null;
    }

    /**
     * @return {@code true} if {@code candidateIp} (a numeric IP literal) falls within any of the
     *         {@code cidrs}. Returns {@code false} for a blank / unparseable candidate or when no
     *         CIDR matches; an unparseable CIDR entry is skipped.
     */
    static boolean matchesAny(Iterable<String> cidrs, String candidateIp) {
        byte[] address = toBytes(candidateIp);
        if (address == null) {
            return false;
        }
        for (String cidr : cidrs) {
            Cidr parsed = parse(cidr);
            if (parsed != null && parsed.contains(address)) {
                return true;
            }
        }
        return false;
    }

    private static Cidr parse(String cidr) {
        if (cidr == null) {
            return null;
        }
        String trimmed = cidr.trim();
        int slash = trimmed.indexOf('/');
        if (slash < 0) {
            return null;
        }
        byte[] network = toBytes(trimmed.substring(0, slash));
        if (network == null) {
            return null;
        }
        int maxPrefix = network.length * 8;
        int prefix;
        try {
            prefix = Integer.parseInt(trimmed.substring(slash + 1).trim());
        } catch (NumberFormatException ex) {
            return null;
        }
        if (prefix < 0 || prefix > maxPrefix) {
            return null;
        }
        return new Cidr(network, prefix);
    }

    /**
     * Parses a numeric IP literal to its address bytes, or {@code null} if it is not a literal.
     * The character allow-list ({@code 0-9 a-f A-F . :}) guarantees {@link InetAddress#getByName}
     * never performs name resolution.
     */
    private static byte[] toBytes(String ip) {
        if (ip == null) {
            return null;
        }
        String trimmed = ip.trim();
        if (trimmed.isEmpty() || !isLiteral(trimmed)) {
            return null;
        }
        try {
            return InetAddress.getByName(trimmed).getAddress();
        } catch (UnknownHostException ex) {
            return null;
        }
    }

    private static boolean isLiteral(String value) {
        boolean hasDot = false;
        boolean hasColon = false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '.') {
                hasDot = true;
            } else if (c == ':') {
                hasColon = true;
            } else if (!isHexDigit(c)) {
                return false;
            }
        }
        // An IPv4 literal has dots, an IPv6 literal has colons; reject bare tokens like "abc".
        return hasDot || hasColon;
    }

    private static boolean isHexDigit(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }

    private record Cidr(byte[] network, int prefixLength) {

        boolean contains(byte[] address) {
            if (address.length != network.length) {
                return false;
            }
            int fullBytes = prefixLength / 8;
            for (int i = 0; i < fullBytes; i++) {
                if (address[i] != network[i]) {
                    return false;
                }
            }
            int remainingBits = prefixLength % 8;
            if (remainingBits == 0) {
                return true;
            }
            int mask = 0xFF << (8 - remainingBits);
            return (address[fullBytes] & mask) == (network[fullBytes] & mask);
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Cidr other
                    && prefixLength == other.prefixLength
                    && Arrays.equals(network, other.network);
        }

        @Override
        public int hashCode() {
            return 31 * Arrays.hashCode(network) + prefixLength;
        }
    }
}
