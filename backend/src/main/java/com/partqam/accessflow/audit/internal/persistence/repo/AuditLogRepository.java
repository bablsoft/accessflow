package com.partqam.accessflow.audit.internal.persistence.repo;

import com.partqam.accessflow.audit.internal.persistence.entity.AuditLogEntity;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AuditLogRepository
        extends JpaRepository<AuditLogEntity, UUID>, JpaSpecificationExecutor<AuditLogEntity> {

    Optional<AuditLogEntity> findTopByOrganizationIdOrderByCreatedAtDescIdDesc(UUID organizationId);

    default List<AuditLogEntity> findForVerification(Specification<AuditLogEntity> spec) {
        return findAll(spec, Sort.by(Sort.Order.asc("createdAt"), Sort.Order.asc("id")));
    }
}
