package com.bablsoft.accessflow.lifecycle.internal.persistence.repo;

import com.bablsoft.accessflow.lifecycle.internal.persistence.entity.LifecycleSaltEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface LifecycleSaltRepository extends JpaRepository<LifecycleSaltEntity, UUID> {
}
