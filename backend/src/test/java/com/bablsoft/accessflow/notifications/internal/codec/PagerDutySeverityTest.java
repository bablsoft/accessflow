package com.bablsoft.accessflow.notifications.internal.codec;

import com.bablsoft.accessflow.notifications.api.NotificationChannelConfigException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PagerDutySeverityTest {

    @Test
    void wireValueIsLowercase() {
        assertThat(PagerDutySeverity.CRITICAL.wireValue()).isEqualTo("critical");
        assertThat(PagerDutySeverity.INFO.wireValue()).isEqualTo("info");
    }

    @Test
    void fromWireParsesCaseInsensitively() {
        assertThat(PagerDutySeverity.fromWire("CRITICAL")).isEqualTo(PagerDutySeverity.CRITICAL);
        assertThat(PagerDutySeverity.fromWire("warning")).isEqualTo(PagerDutySeverity.WARNING);
        assertThat(PagerDutySeverity.fromWire(" Error ")).isEqualTo(PagerDutySeverity.ERROR);
    }

    @Test
    void fromWireRejectsNullOrBlank() {
        assertThatThrownBy(() -> PagerDutySeverity.fromWire(null))
                .isInstanceOf(NotificationChannelConfigException.class)
                .hasMessageContaining("default_severity");
        assertThatThrownBy(() -> PagerDutySeverity.fromWire("  "))
                .isInstanceOf(NotificationChannelConfigException.class);
    }

    @Test
    void fromWireRejectsUnknownValue() {
        assertThatThrownBy(() -> PagerDutySeverity.fromWire("fatal"))
                .isInstanceOf(NotificationChannelConfigException.class)
                .hasMessageContaining("default_severity");
    }
}
