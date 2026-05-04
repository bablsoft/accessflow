package com.partqam.accessflow.security.internal.jwt;

import com.partqam.accessflow.core.api.UserView;
import com.partqam.accessflow.security.api.JwtClaims;

public interface JwtService {
    String generateAccessToken(UserView user);
    String generateRefreshToken(UserView user);
    JwtClaims parseAccessToken(String token);
    JwtClaims parseRefreshToken(String token);
}
