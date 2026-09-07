package com.bablsoft.accessflow.core.internal.config;

import com.bablsoft.accessflow.core.api.PgVectorAvailability;
import com.bablsoft.accessflow.core.api.PgVectorStatus;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Holds the pgvector availability decision made by {@code PgVectorFlywayConfiguration}'s migration
 * strategy while Flyway runs (AF-336). The status defaults to the unavailable
 * {@link PgVectorStatus#EXTENSION_MISSING} and is set exactly once, before the JPA / web layer comes
 * up, so request-time readers always observe the resolved value.
 */
@Component
public class DefaultPgVectorAvailability implements PgVectorAvailability {

    private final AtomicReference<PgVectorStatus> status =
            new AtomicReference<>(PgVectorStatus.EXTENSION_MISSING);

    @Override
    public boolean isAvailable() {
        return status.get() == PgVectorStatus.AVAILABLE;
    }

    @Override
    public PgVectorStatus status() {
        return status.get();
    }

    void set(PgVectorStatus value) {
        status.set(value);
    }
}
