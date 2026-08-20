package com.bablsoft.accessflow.audit.internal.persistence.repo;

import com.bablsoft.accessflow.audit.internal.persistence.entity.AuditSinkEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AuditSinkRepository extends JpaRepository<AuditSinkEntity, UUID> {

    List<AuditSinkEntity> findByOrganizationIdOrderByCreatedAtAsc(UUID organizationId);

    Optional<AuditSinkEntity> findByIdAndOrganizationId(UUID id, UUID organizationId);

    boolean existsByOrganizationIdAndName(UUID organizationId, String name);

    List<AuditSinkEntity> findByEnabledTrueOrderByCreatedAtAsc();
}
