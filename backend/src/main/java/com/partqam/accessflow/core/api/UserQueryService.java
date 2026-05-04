package com.partqam.accessflow.core.api;

import java.util.Optional;
import java.util.UUID;

public interface UserQueryService {
    Optional<UserView> findByEmail(String email);
    Optional<UserView> findById(UUID id);
}
