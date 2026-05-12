package com.bablsoft.accessflow.security.internal.web.model;

public record LoginResponse(
        String accessToken,
        String tokenType,
        long expiresIn,
        UserSummary user
) {}
