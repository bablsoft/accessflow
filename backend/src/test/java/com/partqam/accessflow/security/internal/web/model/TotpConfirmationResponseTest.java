package com.partqam.accessflow.security.internal.web.model;

import com.partqam.accessflow.core.api.TotpConfirmationResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TotpConfirmationResponseTest {

    @Test
    void fromCopiesBackupCodes() {
        var codes = List.of("a", "b", "c");
        var result = new TotpConfirmationResult(codes);

        var response = TotpConfirmationResponse.from(result);

        assertThat(response.backupCodes()).containsExactly("a", "b", "c");
    }
}
