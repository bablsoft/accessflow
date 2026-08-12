package com.bablsoft.accessflow.access.internal.persistence.repo;

import com.bablsoft.accessflow.access.internal.persistence.entity.GrantUsageWatermarkEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface GrantUsageWatermarkRepository
        extends JpaRepository<GrantUsageWatermarkEntity, UUID> {
}
