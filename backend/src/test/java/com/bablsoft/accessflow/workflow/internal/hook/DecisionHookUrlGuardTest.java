package com.bablsoft.accessflow.workflow.internal.hook;

import com.bablsoft.accessflow.workflow.api.IllegalDecisionHookException;
import com.bablsoft.accessflow.workflow.internal.config.DecisionHookProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetAddress;
import java.net.UnknownHostException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DecisionHookUrlGuardTest {

    private final DecisionHookUrlGuard strict =
            new DecisionHookUrlGuard(new DecisionHookProperties(false, null, null));
    private final DecisionHookUrlGuard permissive =
            new DecisionHookUrlGuard(new DecisionHookProperties(true, null, null));

    @ParameterizedTest
    @ValueSource(strings = {"127.0.0.1", "10.1.2.3", "172.16.0.1", "172.31.255.255",
            "192.168.1.1", "169.254.169.254", "100.64.0.1", "100.127.255.254", "0.0.0.0",
            "192.0.0.8", "198.18.0.1", "224.0.0.1", "255.255.255.255", "::1", "::", "fe80::1",
            "fc00::1", "fd12:3456::1", "::ffff:10.0.0.1", "::ffff:169.254.169.254",
            "64:ff9b::a00:1", "2002:a00:1::1", "::a00:1", "64:ff9b:1::808:808", "192.0.2.1",
            "198.51.100.7", "203.0.113.9", "192.88.99.1"})
    void restrictedAddressesAreRecognised(String literal) throws UnknownHostException {
        assertThat(DecisionHookUrlGuard.isRestricted(InetAddress.getByName(literal))).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"8.8.8.8", "1.1.1.1", "100.63.255.255", "100.128.0.1", "172.32.0.1",
            "192.169.0.1", "2001:4860:4860::8888", "::ffff:8.8.8.8", "2002:808:808::1"})
    void publicAddressesAreNotRestricted(String literal) throws UnknownHostException {
        assertThat(DecisionHookUrlGuard.isRestricted(InetAddress.getByName(literal))).isFalse();
    }

    @Test
    void aPublicHttpsLiteralIsAccepted() {
        assertThat(strict.validate("https://8.8.8.8/v1/decide").toString())
                .isEqualTo("https://8.8.8.8/v1/decide");
    }

    @Test
    void plainHttpIsRefusedUnlessPrivateNetworksAreAllowed() {
        assertThatThrownBy(() -> strict.validate("http://8.8.8.8/decide"))
                .isInstanceOf(IllegalDecisionHookException.class)
                .extracting("messageKey").isEqualTo(DecisionHookUrlGuard.KEY_INSECURE_SCHEME);
        assertThat(permissive.validate("http://opa.svc:8181/v1/data").getHost())
                .isEqualTo("opa.svc");
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://127.0.0.1/x", "https://10.0.0.5/x",
            "https://169.254.169.254/latest/meta-data", "https://localhost/x",
            "https://api.localhost/x", "https://[::1]/x"})
    void internalTargetsAreRefused(String url) {
        assertThatThrownBy(() -> strict.validate(url))
                .isInstanceOf(IllegalDecisionHookException.class)
                .extracting("messageKey").isEqualTo(DecisionHookUrlGuard.KEY_RESTRICTED_ADDRESS);
    }

    @Test
    void internalTargetsAreAcceptedWhenPrivateNetworksAreAllowed() {
        assertThat(permissive.validate("https://127.0.0.1:8181/x").getPort()).isEqualTo(8181);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "not a url", "/relative", "ftp://8.8.8.8/x",
            "https://user:pw@8.8.8.8/x", "https://8.8.8.8/x#frag", "https:///nohost"})
    void malformedUrlsAreRefused(String url) {
        assertThatThrownBy(() -> strict.validate(url))
                .isInstanceOf(IllegalDecisionHookException.class)
                .extracting("messageKey").isEqualTo(DecisionHookUrlGuard.KEY_INVALID_URL);
    }

    @Test
    void aNullUrlIsRefused() {
        assertThatThrownBy(() -> strict.validate(null))
                .isInstanceOf(IllegalDecisionHookException.class);
    }

    @Test
    void anUnresolvableHostIsAcceptedAtSaveTime() {
        assertThat(strict.validate("https://no-such-host.invalid/x").getHost())
                .isEqualTo("no-such-host.invalid");
    }

    @Test
    void callTimeResolutionBlocksInternalAddresses() {
        var target = strict.resolve("https://127.0.0.1/x");
        assertThat(target.blocked()).isTrue();
        assertThat(target.uri()).isNull();
    }

    @Test
    void callTimeResolutionBlocksAUrlThatNoLongerParses() {
        assertThat(strict.resolve("http://8.8.8.8/x").blocked()).isTrue();
    }

    @Test
    void callTimeResolutionAllowsAPublicAddress() {
        var target = strict.resolve("https://8.8.8.8/x");
        assertThat(target.blocked()).isFalse();
        assertThat(target.uri()).hasToString("https://8.8.8.8/x");
    }

    @Test
    void callTimeResolutionReportsAnUnresolvableHostWithoutBlocking() {
        var target = strict.resolve("https://no-such-host.invalid/x");
        assertThat(target.blocked()).isFalse();
        assertThat(target.uri()).isNull();
    }

    @Test
    void callTimeResolutionSkipsTheAddressCheckWhenPrivateNetworksAreAllowed() {
        assertThat(permissive.resolve("http://127.0.0.1:9/x").uri()).isNotNull();
    }
}
