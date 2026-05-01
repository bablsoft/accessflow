package com.partqam.accessflow.core.internal.persistence;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface AuditLogRepository extends JpaRepository<AuditLog, UUID> {

    Page<AuditLog> findAllByOrganization_Id(UUID organizationId, Pageable pageable);

    Page<AuditLog> findAllByActor_Id(UUID actorId, Pageable pageable);

    List<AuditLog> findAllByResourceTypeAndResourceId(String resourceType, UUID resourceId);
}
