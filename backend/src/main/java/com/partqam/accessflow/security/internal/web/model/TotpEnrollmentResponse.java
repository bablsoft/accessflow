package com.partqam.accessflow.security.internal.web.model;

import com.partqam.accessflow.core.api.TotpEnrollment;

public record TotpEnrollmentResponse(String secret, String otpauthUrl, String qrDataUri) {

    public static TotpEnrollmentResponse from(TotpEnrollment enrollment) {
        return new TotpEnrollmentResponse(enrollment.secret(), enrollment.otpauthUrl(),
                enrollment.qrDataUri());
    }
}
