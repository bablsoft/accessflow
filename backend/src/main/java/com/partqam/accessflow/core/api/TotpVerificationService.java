package com.partqam.accessflow.core.api;

import java.util.UUID;

public interface TotpVerificationService {

    boolean isEnabled(UUID userId);

    boolean verify(UUID userId, String code);
}
