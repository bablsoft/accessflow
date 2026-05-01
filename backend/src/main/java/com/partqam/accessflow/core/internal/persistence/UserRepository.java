package com.partqam.accessflow.core.internal.persistence;

import com.partqam.accessflow.core.api.UserRoleType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);

    List<User> findAllByOrganization_Id(UUID organizationId);

    List<User> findAllByOrganization_IdAndRole(UUID organizationId, UserRoleType role);
}
