package com.bablsoft.accessflow.access.internal.persistence.repo;

import com.bablsoft.accessflow.access.internal.persistence.entity.GrantUsageSummaryEntity;
import com.bablsoft.accessflow.core.api.GrantResourceKind;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The report page is served through {@link JpaSpecificationExecutor} rather than a JPQL query with
 * optional {@code (:param is null or …)} filters — see {@code GrantUsageSpecifications} for why that
 * idiom cannot work against this table's PostgreSQL enum columns.
 */
public interface GrantUsageSummaryRepository extends JpaRepository<GrantUsageSummaryEntity, UUID>,
        JpaSpecificationExecutor<GrantUsageSummaryEntity> {

    Optional<GrantUsageSummaryEntity>
        findByOrganizationIdAndResourceKindAndResourceIdAndUserId(
            UUID organizationId, GrantResourceKind resourceKind, UUID resourceId, UUID userId);

    List<GrantUsageSummaryEntity> findByOrganizationId(UUID organizationId);

    List<GrantUsageSummaryEntity> findByOrganizationIdAndResourceKind(
            UUID organizationId, GrantResourceKind resourceKind);
}
