package com.bablsoft.accessflow.discovery.internal.detect;

import com.bablsoft.accessflow.core.api.DataClassification;
import com.bablsoft.accessflow.discovery.api.DiscoveryDetector;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class PhoneDetectorTest {

    private final PhoneDetector detector = new PhoneDetector();

    @Test
    void exposesTypeAndClassification() {
        assertThat(detector.type()).isEqualTo(DiscoveryDetector.PHONE);
        assertThat(detector.classification()).isEqualTo(DataClassification.PII);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "+14155552671",
            "+1 415 555 2671",
            "(415) 555-2671",
            "415-555-2671",
            "415.555.2671",
            "+49 30 901820",
            "020 7946 0958"
    })
    void matchesValidPhoneNumbers(String value) {
        assertThat(detector.matches(value)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "1234567",          // bare digit run without + or punctuation — likely an id
            "1234567890123456", // 16 digits — too long
            "+123456",          // 6 digits — too short
            "phone: 415",
            "hello",
            "415-55",           // too few digits
            "()-. -"            // punctuation only
    })
    void rejectsInvalidValues(String value) {
        assertThat(detector.matches(value)).isFalse();
    }

    @Test
    void orderedListRunsStricterDetectorsFirst() {
        var order = ValueDetector.ORDERED.stream().map(d -> d.type().name()).toList();
        assertThat(order).containsExactly("CREDIT_CARD", "IBAN", "EMAIL", "SSN", "PHONE");
    }
}
