package com.bablsoft.accessflow.notifications.internal;

import com.bablsoft.accessflow.notifications.internal.config.TicketingProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Rejects replayed ticketing callbacks (AF-453). Each verified {@code X-AccessFlow-Signature} is
 * recorded in Redis for the signature-tolerance window; a second sighting of the same signature
 * within that window is a replay. The window matches the timestamp acceptance window, so an
 * attacker cannot replay a captured request after it would already be rejected as stale.
 */
@Service
@RequiredArgsConstructor
public class TicketingReplayGuard {

    private static final String PREFIX = "ticketing:sig:";

    private final StringRedisTemplate redisTemplate;
    private final TicketingProperties properties;

    /** Returns {@code true} the first time a signature is seen, {@code false} on replay. */
    public boolean firstSeen(String signature) {
        if (signature == null || signature.isBlank()) {
            return false;
        }
        var stored = redisTemplate.opsForValue()
                .setIfAbsent(PREFIX + signature, "1", properties.signatureTolerance());
        return Boolean.TRUE.equals(stored);
    }
}
