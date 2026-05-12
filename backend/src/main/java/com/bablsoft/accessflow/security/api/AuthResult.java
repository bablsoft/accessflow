package com.bablsoft.accessflow.security.api;

import com.bablsoft.accessflow.core.api.UserView;

public record AuthResult(
        String accessToken,
        String refreshToken,
        String tokenType,
        long expiresIn,
        UserView user
) {}
