package com.bablsoft.accessflow.apigov.internal.persistence.repo;

import com.bablsoft.accessflow.apigov.internal.persistence.entity.ApiConnectorHealthCheckEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface ApiConnectorHealthCheckRepository extends JpaRepository<ApiConnectorHealthCheckEntity, UUID> {

    List<ApiConnectorHealthCheckEntity> findTop20ByConnectorIdOrderByCheckedAtDesc(UUID connectorId);

    @Modifying
    @Transactional
    @Query("delete from ApiConnectorHealthCheckEntity c where c.checkedAt < :cutoff")
    int deleteByCheckedAtBefore(@Param("cutoff") Instant cutoff);
}
