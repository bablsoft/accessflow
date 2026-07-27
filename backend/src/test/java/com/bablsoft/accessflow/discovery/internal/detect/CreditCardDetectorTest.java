package com.bablsoft.accessflow.discovery.internal.detect;

import com.bablsoft.accessflow.core.api.DataClassification;
import com.bablsoft.accessflow.discovery.api.DiscoveryDetector;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class CreditCardDetectorTest {

    private final CreditCardDetector detector = new CreditCardDetector();

    @Test
    void exposesTypeAndClassification() {
        assertThat(detector.type()).isEqualTo(DiscoveryDetector.CREDIT_CARD);
        assertThat(detector.classification()).isEqualTo(DataClassification.PCI);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "4111111111111111",          // Visa test PAN
            "4111 1111 1111 1111",       // spaces
            "4111-1111-1111-1111",       // dashes
            "5500005555555559",          // Mastercard test PAN
            "340000000000009",           // Amex (15 digits)
            "6011000990139424",          // Discover
            "3566002020360505"           // JCB
    })
    void matchesLuhnValidPans(String value) {
        assertThat(detector.matches(value)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "4111111111111112",  // Luhn-invalid
            "1234567890123456",  // Luhn-invalid
            "411111111111",      // 12 digits — too short
            "41111111111111111111", // 20 digits — too long
            "4111a11111111111",  // letter
            "hello world",
            "079-05-1120"        // SSN shape, not a PAN
    })
    void rejectsInvalidValues(String value) {
        assertThat(detector.matches(value)).isFalse();
    }

    @Test
    void rejectsOversizedValue() {
        assertThat(detector.matches("4111111111111111" + " 0".repeat(20))).isFalse();
    }
}
