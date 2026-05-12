package com.bablsoft.accessflow.security.internal.token;

public interface RefreshTokenStore {
    void store(String token, String userId, long ttlSeconds);
    boolean isRevoked(String token);
    void revoke(String token);
    void revokeAllForUser(String userId);
}
