package com.bablsoft.accessflow.core.internal.persistence.repo;

import com.bablsoft.accessflow.core.internal.persistence.entity.RolePermissionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface RolePermissionRepository
        extends JpaRepository<RolePermissionEntity, RolePermissionEntity.Key> {

    List<RolePermissionEntity> findAllByRole_Id(UUID roleId);

    void deleteAllByRole_Id(UUID roleId);
}
