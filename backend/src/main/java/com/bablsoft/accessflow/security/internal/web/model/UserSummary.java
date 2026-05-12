package com.bablsoft.accessflow.security.internal.web.model;

import java.util.UUID;

public record UserSummary(
        UUID id,
        String email,
        String displayName,
        String role,
        String authProvider,
        boolean totpEnabled,
        String preferredLanguage
) {}
