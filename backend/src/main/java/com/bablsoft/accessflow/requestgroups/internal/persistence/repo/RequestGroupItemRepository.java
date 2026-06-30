package com.bablsoft.accessflow.requestgroups.internal.persistence.repo;

import com.bablsoft.accessflow.requestgroups.internal.persistence.entity.RequestGroupItemEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface RequestGroupItemRepository extends JpaRepository<RequestGroupItemEntity, UUID> {

    List<RequestGroupItemEntity> findByGroupIdOrderBySequenceOrderAsc(UUID groupId);

    void deleteByGroupId(UUID groupId);
}
