package com.partqam.accessflow.core.internal.persistence.repo;

import com.partqam.accessflow.core.api.UserRoleType;
import com.partqam.accessflow.core.internal.persistence.entity.UserEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<UserEntity, UUID> {

    Optional<UserEntity> findByEmail(String email);

    boolean existsByEmail(String email);

    boolean existsByRoleAndActive(UserRoleType role, boolean active);

    List<UserEntity> findAllByOrganization_Id(UUID organizationId);

    Page<UserEntity> findAllByOrganization_Id(UUID organizationId, Pageable pageable);

    List<UserEntity> findAllByOrganization_IdAndRole(UUID organizationId, UserRoleType role);
}
