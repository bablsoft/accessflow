package com.bablsoft.accessflow.security.internal;

import com.bablsoft.accessflow.security.internal.config.StepUpProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

/**
 * Single-use step-up tokens kept in Redis (AF-444), mirroring {@code OAuth2ExchangeCodeStore}.
 * A token is bound to one user id and self-destructs on first {@link #consume(String) consume} or
 * when its TTL elapses, so a tap can be acted on at most once and only within the step-up window.
 */
@Service
@RequiredArgsConstructor
public class StepUpCodeStore {

    private static final String PREFIX = "stepup:";
    private static final int TOKEN_BYTES = 32;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final StringRedisTemplate redisTemplate;
    private final StepUpProperties properties;

    public String issue(UUID userId) {
        var token = randomToken();
        redisTemplate.opsForValue().set(PREFIX + token, userId.toString(), properties.ttl());
        return token;
    }

    public Optional<UUID> consume(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        var value = redisTemplate.opsForValue().getAndDelete(PREFIX + token);
        if (value == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(value));
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
    }

    private static String randomToken() {
        var bytes = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
