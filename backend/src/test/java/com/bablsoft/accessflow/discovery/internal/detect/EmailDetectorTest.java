package com.bablsoft.accessflow.discovery.internal.detect;

import com.bablsoft.accessflow.core.api.DataClassification;
import com.bablsoft.accessflow.discovery.api.DiscoveryDetector;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class EmailDetectorTest {

    private final EmailDetector detector = new EmailDetector();

    @Test
    void exposesTypeAndClassification() {
        assertThat(detector.type()).isEqualTo(DiscoveryDetector.EMAIL);
        assertThat(detector.classification()).isEqualTo(DataClassification.PII);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "alice@example.com",
            "bob.smith+tag@sub.example.co.uk",
            "  padded@example.org  ",
            "UPPER.CASE@EXAMPLE.COM",
            "a_b-c%d@example.io"
    })
    void matchesValidEmails(String value) {
        assertThat(detector.matches(value)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "not-an-email",
            "missing-at.example.com",
            "two@@example.com",
            "no-tld@example",
            "spaces in@example.com",
            "@example.com",
            "user@",
            "12345"
    })
    void rejectsInvalidValues(String value) {
        assertThat(detector.matches(value)).isFalse();
    }

    @Test
    void rejectsOversizedValue() {
        assertThat(detector.matches("a".repeat(320) + "@example.com")).isFalse();
    }
}
