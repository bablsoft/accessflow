package com.partqam.accessflow.security.internal.web.model;

import com.partqam.accessflow.core.api.TotpConfirmationResult;

import java.util.List;

public record TotpConfirmationResponse(List<String> backupCodes) {

    public static TotpConfirmationResponse from(TotpConfirmationResult result) {
        return new TotpConfirmationResponse(result.backupCodes());
    }
}
