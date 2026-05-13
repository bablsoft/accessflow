package com.bablsoft.accessflow.core.internal.persistence.repo;

import com.bablsoft.accessflow.core.internal.persistence.entity.SystemSmtpConfigEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface SystemSmtpConfigRepository extends JpaRepository<SystemSmtpConfigEntity, UUID> {

    Optional<SystemSmtpConfigEntity> findByOrganizationId(UUID organizationId);

    void deleteByOrganizationId(UUID organizationId);
}
