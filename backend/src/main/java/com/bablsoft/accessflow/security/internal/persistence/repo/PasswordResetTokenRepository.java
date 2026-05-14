package com.bablsoft.accessflow.security.internal.persistence.repo;

import com.bablsoft.accessflow.security.api.PasswordResetStatusType;
import com.bablsoft.accessflow.security.internal.persistence.entity.PasswordResetTokenEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetTokenEntity, UUID> {

    Optional<PasswordResetTokenEntity> findByTokenHash(String tokenHash);

    Optional<PasswordResetTokenEntity> findFirstByUserIdAndStatus(UUID userId,
                                                                  PasswordResetStatusType status);
}
