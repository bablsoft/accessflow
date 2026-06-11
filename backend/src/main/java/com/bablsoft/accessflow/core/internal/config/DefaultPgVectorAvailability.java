package com.bablsoft.accessflow.core.internal.config;

import com.bablsoft.accessflow.core.api.PgVectorAvailability;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Holds the pgvector availability decision made by {@code PgVectorFlywayConfiguration}'s migration
 * strategy while Flyway runs (AF-336). The flag defaults to {@code false} and is set exactly once,
 * before the JPA / web layer comes up, so request-time readers always observe the resolved value.
 */
@Component
public class DefaultPgVectorAvailability implements PgVectorAvailability {

    private final AtomicBoolean available = new AtomicBoolean(false);

    @Override
    public boolean isAvailable() {
        return available.get();
    }

    void set(boolean value) {
        available.set(value);
    }
}
