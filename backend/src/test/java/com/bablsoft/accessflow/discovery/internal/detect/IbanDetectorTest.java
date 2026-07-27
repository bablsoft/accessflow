package com.bablsoft.accessflow.discovery.internal.detect;

import com.bablsoft.accessflow.core.api.DataClassification;
import com.bablsoft.accessflow.discovery.api.DiscoveryDetector;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class IbanDetectorTest {

    private final IbanDetector detector = new IbanDetector();

    @Test
    void exposesTypeAndClassification() {
        assertThat(detector.type()).isEqualTo(DiscoveryDetector.IBAN);
        assertThat(detector.classification()).isEqualTo(DataClassification.FINANCIAL);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "DE89370400440532013000",
            "GB29NWBK60161331926819",
            "FR1420041010050500013M02606",
            "DE89 3704 0044 0532 0130 00",   // grouped with spaces
            "nl91abna0417164300"             // lower case tolerated
    })
    void matchesValidIbans(String value) {
        assertThat(detector.matches(value)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "DE89370400440532013001",     // checksum broken
            "DE8937040044053201300",      // wrong length for DE
            "ZZ89370400440532013000",     // unknown country
            "D189370400440532013000",     // digit in country code
            "GB29NWBK6016133192681",      // wrong length for GB
            "not an iban",
            "4111111111111111"
    })
    void rejectsInvalidValues(String value) {
        assertThat(detector.matches(value)).isFalse();
    }
}
