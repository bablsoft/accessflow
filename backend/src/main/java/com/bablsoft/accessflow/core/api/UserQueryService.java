package com.bablsoft.accessflow.core.api;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserQueryService {
    Optional<UserView> findByEmail(String email);
    Optional<UserView> findById(UUID id);
    List<UserView> findByOrganizationAndRole(UUID organizationId, UserRoleType role);
}
