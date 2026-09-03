package com.bablsoft.accessflow.api.internal.web;

import com.bablsoft.accessflow.api.internal.UpdateCheckStatus;
import com.bablsoft.accessflow.api.internal.UpdateStatusView;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class SystemUpdateStatusResponseTest {

    @Test
    void mapsEveryField() {
        var at = Instant.parse("2026-09-03T10:00:00Z");
        var view = new UpdateStatusView("2.4.0", "2.5.0", true,
                "https://accessflow.io/changelog/#v2-5-0", at, UpdateCheckStatus.UPDATE_AVAILABLE);

        var response = SystemUpdateStatusResponse.from(view);

        assertThat(response.currentVersion()).isEqualTo("2.4.0");
        assertThat(response.latestVersion()).isEqualTo("2.5.0");
        assertThat(response.updateAvailable()).isTrue();
        assertThat(response.changelogUrl()).isEqualTo("https://accessflow.io/changelog/#v2-5-0");
        assertThat(response.checkedAt()).isEqualTo(at);
        assertThat(response.status()).isEqualTo(UpdateCheckStatus.UPDATE_AVAILABLE);
    }

    @Test
    void carriesNullsFromAnUnknownSnapshot() {
        var response = SystemUpdateStatusResponse.from(UpdateStatusView.unknown(null, null));

        assertThat(response.currentVersion()).isNull();
        assertThat(response.latestVersion()).isNull();
        assertThat(response.updateAvailable()).isFalse();
        assertThat(response.changelogUrl()).isNull();
        assertThat(response.checkedAt()).isNull();
        assertThat(response.status()).isEqualTo(UpdateCheckStatus.UNKNOWN);
    }
}
