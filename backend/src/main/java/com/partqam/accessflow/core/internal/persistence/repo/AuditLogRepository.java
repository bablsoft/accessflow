package com.partqam.accessflow.core.internal.persistence.repo;

import com.partqam.accessflow.core.internal.persistence.entity.AuditLogEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface AuditLogRepository extends JpaRepository<AuditLogEntity, UUID> {

    Page<AuditLogEntity> findAllByOrganization_Id(UUID organizationId, Pageable pageable);

    Page<AuditLogEntity> findAllByActor_Id(UUID actorId, Pageable pageable);

    List<AuditLogEntity> findAllByResourceTypeAndResourceId(String resourceType, UUID resourceId);
}
