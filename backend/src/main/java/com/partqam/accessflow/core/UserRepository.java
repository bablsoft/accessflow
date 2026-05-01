package com.partqam.accessflow.core;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);

    List<User> findAllByOrganizationId(UUID organizationId);

    List<User> findAllByOrganizationIdAndRole(UUID organizationId, UserRoleType role);
}
