package com.bablsoft.accessflow.core.internal.persistence.repo;

import com.bablsoft.accessflow.core.internal.persistence.entity.QueryRequestResultEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface QueryRequestResultRepository
        extends JpaRepository<QueryRequestResultEntity, UUID> {
}
