package com.bablsoft.accessflow.workflow.internal.routing;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CidrMatcherTest {

    @Test
    void ipv4WithinRange() {
        assertThat(CidrMatcher.matchesAny(List.of("203.0.113.0/24"), "203.0.113.7")).isTrue();
        assertThat(CidrMatcher.matchesAny(List.of("10.0.0.0/8"), "10.255.1.2")).isTrue();
        assertThat(CidrMatcher.matchesAny(List.of("192.168.1.0/32"), "192.168.1.0")).isTrue();
    }

    @Test
    void ipv4OutsideRange() {
        assertThat(CidrMatcher.matchesAny(List.of("203.0.113.0/24"), "203.0.114.7")).isFalse();
        assertThat(CidrMatcher.matchesAny(List.of("192.168.1.0/32"), "192.168.1.1")).isFalse();
    }

    @Test
    void anyCidrInListMatches() {
        var cidrs = List.of("10.0.0.0/8", "203.0.113.0/24");
        assertThat(CidrMatcher.matchesAny(cidrs, "203.0.113.99")).isTrue();
        assertThat(CidrMatcher.matchesAny(cidrs, "198.51.100.1")).isFalse();
    }

    @Test
    void ipv6WithinRange() {
        assertThat(CidrMatcher.matchesAny(List.of("2001:db8::/32"), "2001:db8::1")).isTrue();
        assertThat(CidrMatcher.matchesAny(List.of("2001:db8:abcd::/48"), "2001:db8:abcd:1::5"))
                .isTrue();
    }

    @Test
    void ipv6OutsideRange() {
        assertThat(CidrMatcher.matchesAny(List.of("2001:db8::/32"), "2001:dead::1")).isFalse();
    }

    @Test
    void mixedFamiliesNeverMatchAcross() {
        assertThat(CidrMatcher.matchesAny(List.of("2001:db8::/32"), "203.0.113.7")).isFalse();
        assertThat(CidrMatcher.matchesAny(List.of("203.0.113.0/24"), "2001:db8::1")).isFalse();
    }

    @Test
    void blankOrUnparseableCandidateNeverMatches() {
        assertThat(CidrMatcher.matchesAny(List.of("203.0.113.0/24"), null)).isFalse();
        assertThat(CidrMatcher.matchesAny(List.of("203.0.113.0/24"), "")).isFalse();
        assertThat(CidrMatcher.matchesAny(List.of("203.0.113.0/24"), "not-an-ip")).isFalse();
    }

    @Test
    void unparseableCidrEntryIsSkipped() {
        assertThat(CidrMatcher.matchesAny(List.of("garbage", "203.0.113.0/24"), "203.0.113.7"))
                .isTrue();
        assertThat(CidrMatcher.matchesAny(List.of("garbage"), "203.0.113.7")).isFalse();
    }

    @Test
    void validCidrSyntax() {
        assertThat(CidrMatcher.isValidCidr("203.0.113.0/24")).isTrue();
        assertThat(CidrMatcher.isValidCidr("10.0.0.0/8")).isTrue();
        assertThat(CidrMatcher.isValidCidr("0.0.0.0/0")).isTrue();
        assertThat(CidrMatcher.isValidCidr("2001:db8::/32")).isTrue();
        assertThat(CidrMatcher.isValidCidr("::/0")).isTrue();
    }

    @Test
    void invalidCidrSyntax() {
        assertThat(CidrMatcher.isValidCidr(null)).isFalse();
        assertThat(CidrMatcher.isValidCidr("203.0.113.0")).isFalse();          // no prefix
        assertThat(CidrMatcher.isValidCidr("203.0.113.0/33")).isFalse();        // prefix too large
        assertThat(CidrMatcher.isValidCidr("203.0.113.0/-1")).isFalse();        // negative prefix
        assertThat(CidrMatcher.isValidCidr("203.0.113.0/abc")).isFalse();       // non-numeric prefix
        assertThat(CidrMatcher.isValidCidr("example.com/24")).isFalse();        // hostname, not literal
        assertThat(CidrMatcher.isValidCidr("2001:db8::/129")).isFalse();        // ipv6 prefix too large
        assertThat(CidrMatcher.isValidCidr("999.0.0.0/8")).isFalse();           // octet out of range
    }
}
