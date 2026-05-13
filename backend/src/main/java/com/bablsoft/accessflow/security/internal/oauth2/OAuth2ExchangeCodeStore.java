package com.bablsoft.accessflow.security.internal.oauth2;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

/**
 * One-time exchange codes the OAuth2 success handler hands to the browser via a redirect:
 * {@code POST /auth/oauth2/exchange} consumes the code and issues the JWT pair. Keeps short-lived
 * (60s default) keys in Redis so a leaked redirect URL cannot be replayed after the user lands.
 */
@Service
@RequiredArgsConstructor
public class OAuth2ExchangeCodeStore {

    private static final String PREFIX = "oauth2:exchange:";
    private static final int CODE_BYTES = 32;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final StringRedisTemplate redisTemplate;
    private final OAuth2RedirectProperties properties;

    public String issue(UUID userId) {
        var code = randomCode();
        var ttl = properties.exchangeCodeTtl() != null
                ? properties.exchangeCodeTtl()
                : Duration.ofMinutes(1);
        redisTemplate.opsForValue().set(PREFIX + code, userId.toString(), ttl);
        return code;
    }

    public Optional<UUID> consume(String code) {
        if (code == null || code.isBlank()) {
            return Optional.empty();
        }
        var key = PREFIX + code;
        var value = redisTemplate.opsForValue().getAndDelete(key);
        if (value == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(value));
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
    }

    private static String randomCode() {
        var bytes = new byte[CODE_BYTES];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
