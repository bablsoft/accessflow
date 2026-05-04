package com.partqam.accessflow.security.internal.token;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Set;

@Service
@RequiredArgsConstructor
class RedisRefreshTokenStore implements RefreshTokenStore {

    private static final String ACTIVE_PREFIX = "refresh:active:";
    private static final String USER_TOKENS_PREFIX = "refresh:user:";

    private final StringRedisTemplate redisTemplate;

    @Override
    public void store(String token, String userId, long ttlSeconds) {
        var hash = hash(token);
        redisTemplate.opsForValue().set(ACTIVE_PREFIX + hash, userId, Duration.ofSeconds(ttlSeconds));
        redisTemplate.opsForSet().add(USER_TOKENS_PREFIX + userId, hash);
        redisTemplate.expire(USER_TOKENS_PREFIX + userId, Duration.ofSeconds(ttlSeconds));
    }

    @Override
    public boolean isRevoked(String token) {
        var hash = hash(token);
        return !Boolean.TRUE.equals(redisTemplate.hasKey(ACTIVE_PREFIX + hash));
    }

    @Override
    public void revoke(String token) {
        var hash = hash(token);
        var userId = redisTemplate.opsForValue().get(ACTIVE_PREFIX + hash);
        redisTemplate.delete(ACTIVE_PREFIX + hash);
        if (userId != null) {
            redisTemplate.opsForSet().remove(USER_TOKENS_PREFIX + userId, hash);
        }
    }

    @Override
    public void revokeAllForUser(String userId) {
        Set<String> hashes = redisTemplate.opsForSet().members(USER_TOKENS_PREFIX + userId);
        if (hashes != null) {
            hashes.forEach(hash -> redisTemplate.delete(ACTIVE_PREFIX + hash));
        }
        redisTemplate.delete(USER_TOKENS_PREFIX + userId);
    }

    private String hash(String token) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            var bytes = digest.digest(token.getBytes());
            return HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
