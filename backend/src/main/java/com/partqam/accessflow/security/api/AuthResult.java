package com.partqam.accessflow.security.api;

import com.partqam.accessflow.core.api.UserView;

public record AuthResult(
        String accessToken,
        String refreshToken,
        String tokenType,
        long expiresIn,
        UserView user
) {}
