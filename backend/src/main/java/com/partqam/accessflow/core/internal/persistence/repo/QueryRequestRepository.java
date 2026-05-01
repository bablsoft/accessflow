package com.partqam.accessflow.core.internal.persistence.repo;

import com.partqam.accessflow.core.api.QueryStatus;
import com.partqam.accessflow.core.internal.persistence.entity.QueryRequestEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface QueryRequestRepository extends JpaRepository<QueryRequestEntity, UUID> {

    Page<QueryRequestEntity> findAllByDatasource_Id(UUID datasourceId, Pageable pageable);

    Page<QueryRequestEntity> findAllBySubmittedBy_Id(UUID userId, Pageable pageable);

    Page<QueryRequestEntity> findAllByStatus(QueryStatus status, Pageable pageable);

    List<QueryRequestEntity> findAllByStatus(QueryStatus status);
}
