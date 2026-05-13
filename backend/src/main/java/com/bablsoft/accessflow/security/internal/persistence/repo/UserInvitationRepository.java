package com.bablsoft.accessflow.security.internal.persistence.repo;

import com.bablsoft.accessflow.security.api.UserInvitationStatusType;
import com.bablsoft.accessflow.security.internal.persistence.entity.UserInvitationEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface UserInvitationRepository extends JpaRepository<UserInvitationEntity, UUID> {

    Optional<UserInvitationEntity> findByIdAndOrganizationId(UUID id, UUID organizationId);

    Optional<UserInvitationEntity> findByTokenHash(String tokenHash);

    boolean existsByOrganizationIdAndEmailIgnoreCaseAndStatus(UUID organizationId, String email,
                                                              UserInvitationStatusType status);

    Page<UserInvitationEntity> findAllByOrganizationId(UUID organizationId, Pageable pageable);
}
