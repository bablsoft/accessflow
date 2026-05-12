package com.bablsoft.accessflow.security.internal.jwt;

import com.bablsoft.accessflow.core.api.UserView;
import com.bablsoft.accessflow.security.api.JwtClaims;

public interface JwtService {
    String generateAccessToken(UserView user);
    String generateRefreshToken(UserView user);
    JwtClaims parseAccessToken(String token);
    JwtClaims parseRefreshToken(String token);
}
