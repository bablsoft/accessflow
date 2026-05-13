package com.bablsoft.accessflow.security.internal.persistence.repo;

import com.bablsoft.accessflow.security.api.OAuth2ProviderType;
import com.bablsoft.accessflow.security.internal.persistence.entity.OAuth2ConfigEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OAuth2ConfigRepository extends JpaRepository<OAuth2ConfigEntity, UUID> {

    List<OAuth2ConfigEntity> findAllByOrganizationId(UUID organizationId);

    Optional<OAuth2ConfigEntity> findByOrganizationIdAndProvider(UUID organizationId,
                                                                 OAuth2ProviderType provider);

    List<OAuth2ConfigEntity> findAllByOrganizationIdAndActiveTrue(UUID organizationId);

    void deleteByOrganizationIdAndProvider(UUID organizationId, OAuth2ProviderType provider);
}
