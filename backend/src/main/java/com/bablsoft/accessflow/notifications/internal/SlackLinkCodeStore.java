package com.bablsoft.accessflow.notifications.internal;

import com.bablsoft.accessflow.notifications.internal.config.SlackProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

/**
 * One-time Slack account-link codes. A user generates a code in AccessFlow, pastes it into Slack
 * via {@code /accessflow link <code>}, and the verified slash command consumes it. Codes are
 * single-use ({@code getAndDelete}) and expire after
 * {@code accessflow.notifications.slack.link-code-ttl}.
 */
@Service
@RequiredArgsConstructor
public class SlackLinkCodeStore {

    private static final String PREFIX = "slack:link:";
    private static final int CODE_BYTES = 24;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final StringRedisTemplate redisTemplate;
    private final SlackProperties properties;

    public Issued issue(UUID userId) {
        var code = randomCode();
        var ttl = ttl();
        redisTemplate.opsForValue().set(PREFIX + code, userId.toString(), ttl);
        return new Issued(code, Instant.now().plus(ttl));
    }

    public Optional<UUID> consume(String code) {
        if (code == null || code.isBlank()) {
            return Optional.empty();
        }
        var value = redisTemplate.opsForValue().getAndDelete(PREFIX + code.trim());
        if (value == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(value));
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
    }

    private Duration ttl() {
        return properties.linkCodeTtl();
    }

    private static String randomCode() {
        var bytes = new byte[CODE_BYTES];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public record Issued(String code, Instant expiresAt) {
    }
}
