package com.bablsoft.accessflow.core.internal.persistence.repo;

import com.bablsoft.accessflow.core.internal.persistence.entity.UserGroupMembershipEntity;
import com.bablsoft.accessflow.core.internal.persistence.entity.UserGroupMembershipSource;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface UserGroupMembershipRepository
        extends JpaRepository<UserGroupMembershipEntity, UserGroupMembershipEntity.Id> {

    List<UserGroupMembershipEntity> findAllByUser_Id(UUID userId);

    List<UserGroupMembershipEntity> findAllByGroup_Id(UUID groupId);

    long countByGroup_Id(UUID groupId);

    boolean existsByUser_IdAndGroup_Id(UUID userId, UUID groupId);

    @Query("select m.group.id from UserGroupMembershipEntity m where m.user.id = :userId")
    List<UUID> findGroupIdsForUser(@Param("userId") UUID userId);

    @Query("select distinct m.user.id from UserGroupMembershipEntity m "
            + "where m.group.id in :groupIds")
    List<UUID> findUserIdsInGroups(@Param("groupIds") List<UUID> groupIds);

    @Modifying
    @Query("delete from UserGroupMembershipEntity m where m.user.id = :userId and m.source = :source")
    int deleteByUserIdAndSource(@Param("userId") UUID userId,
                                @Param("source") UserGroupMembershipSource source);

    @Modifying
    @Query("delete from UserGroupMembershipEntity m where m.group.id = :groupId")
    int deleteByGroupId(@Param("groupId") UUID groupId);

    @Modifying
    @Query("delete from UserGroupMembershipEntity m where m.user.id = :userId and m.group.id = :groupId")
    int deleteByUserIdAndGroupId(@Param("userId") UUID userId, @Param("groupId") UUID groupId);
}
