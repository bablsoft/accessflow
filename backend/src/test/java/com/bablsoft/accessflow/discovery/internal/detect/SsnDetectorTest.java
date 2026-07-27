package com.bablsoft.accessflow.discovery.internal.detect;

import com.bablsoft.accessflow.core.api.DataClassification;
import com.bablsoft.accessflow.discovery.api.DiscoveryDetector;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class SsnDetectorTest {

    private final SsnDetector detector = new SsnDetector();

    @Test
    void exposesTypeAndClassification() {
        assertThat(detector.type()).isEqualTo(DiscoveryDetector.SSN);
        assertThat(detector.classification()).isEqualTo(DataClassification.PII);
    }

    @ParameterizedTest
    @ValueSource(strings = {"079-05-1120", "079051120", "  123-45-6789  ", "899-99-9999"})
    void matchesValidSsns(String value) {
        assertThat(detector.matches(value)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "000-12-3456",  // area 000 never issued
            "666-12-3456",  // area 666 never issued
            "900-12-3456",  // area >= 900 never issued
            "123-00-3456",  // group 00
            "123-45-0000",  // serial 0000
            "123-456-789",  // wrong grouping
            "12-345-6789",
            "1234567890",   // 10 digits
            "abc-de-fghi"
    })
    void rejectsInvalidValues(String value) {
        assertThat(detector.matches(value)).isFalse();
    }
}
