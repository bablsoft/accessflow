package com.bablsoft.accessflow.core.internal.persistence.repo;

import com.bablsoft.accessflow.core.internal.persistence.entity.DatasourceReviewerEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DatasourceReviewerRepository extends JpaRepository<DatasourceReviewerEntity, UUID> {

    List<DatasourceReviewerEntity> findAllByDatasource_Id(UUID datasourceId);

    boolean existsByDatasource_Id(UUID datasourceId);

    Optional<DatasourceReviewerEntity> findByDatasource_IdAndUser_Id(UUID datasourceId, UUID userId);

    Optional<DatasourceReviewerEntity> findByDatasource_IdAndGroup_Id(UUID datasourceId,
                                                                      UUID groupId);

    @Modifying
    @Query("delete from DatasourceReviewerEntity dr where dr.group.id = :groupId")
    int deleteByGroupId(@Param("groupId") UUID groupId);
}
