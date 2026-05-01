package com.partqam.accessflow.core.internal.persistence;

import com.partqam.accessflow.core.api.QueryStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface QueryRequestRepository extends JpaRepository<QueryRequest, UUID> {

    Page<QueryRequest> findAllByDatasource_Id(UUID datasourceId, Pageable pageable);

    Page<QueryRequest> findAllBySubmittedBy_Id(UUID userId, Pageable pageable);

    Page<QueryRequest> findAllByStatus(QueryStatus status, Pageable pageable);

    List<QueryRequest> findAllByStatus(QueryStatus status);
}
