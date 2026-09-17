package com.bablsoft.accessflow.serviceaccounts.internal.persistence.entity;

import com.bablsoft.accessflow.serviceaccounts.api.ServiceAccountSource;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ServiceAccountEntityTest {

    @Test
    void freshEntityIsUnrestrictedAndStampsBothTimestamps() {
        var entity = new ServiceAccountEntity();

        assertThat(entity.getMcpToolAllowList()).isNull();
        assertThat(entity.getRateLimitPerMinute()).isNull();
        assertThat(entity.getRateLimitPerDay()).isNull();
        assertThat(entity.getOwnerUserId()).isNull();
        assertThat(entity.getVersion()).isZero();
        assertThat(entity.getCreatedAt()).isNotNull();
        assertThat(entity.getUpdatedAt()).isNotNull();
    }

    @Test
    void onUpdateAdvancesUpdatedAtOnly() {
        var entity = new ServiceAccountEntity();
        entity.setUserId(UUID.randomUUID());
        entity.setManagedBy(ServiceAccountSource.UI);
        var created = entity.getCreatedAt();
        var epoch = Instant.EPOCH;
        entity.setUpdatedAt(epoch);

        entity.onUpdate();

        assertThat(entity.getUpdatedAt()).isAfter(epoch);
        assertThat(entity.getCreatedAt()).isEqualTo(created);
        assertThat(entity.getManagedBy()).isEqualTo(ServiceAccountSource.UI);
    }
}
