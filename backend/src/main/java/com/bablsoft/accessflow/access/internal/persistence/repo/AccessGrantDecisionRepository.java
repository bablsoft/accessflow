package com.bablsoft.accessflow.access.internal.persistence.repo;

import com.bablsoft.accessflow.access.internal.persistence.entity.AccessGrantDecisionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface AccessGrantDecisionRepository
        extends JpaRepository<AccessGrantDecisionEntity, UUID> {

    List<AccessGrantDecisionEntity> findAllByAccessGrantRequest_IdOrderByDecidedAtAsc(
            UUID accessGrantRequestId);

    List<AccessGrantDecisionEntity> findAllByAccessGrantRequest_IdAndStage(
            UUID accessGrantRequestId, int stage);
}
