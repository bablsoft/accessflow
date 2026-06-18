package com.bablsoft.accessflow.core.internal;

import com.bablsoft.accessflow.core.api.CreateOrganizationCommand;
import com.bablsoft.accessflow.core.api.OrganizationAdminService;
import com.bablsoft.accessflow.core.api.OrganizationNotFoundException;
import com.bablsoft.accessflow.core.api.OrganizationProvisioningService;
import com.bablsoft.accessflow.core.api.OrganizationUsageView;
import com.bablsoft.accessflow.core.api.OrganizationView;
import com.bablsoft.accessflow.core.api.PageRequest;
import com.bablsoft.accessflow.core.api.PageResponse;
import com.bablsoft.accessflow.core.api.UpdateOrganizationCommand;
import com.bablsoft.accessflow.core.internal.persistence.entity.OrganizationEntity;
import com.bablsoft.accessflow.core.internal.persistence.repo.OrganizationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.UUID;

@Service
@RequiredArgsConstructor
class DefaultOrganizationAdminService implements OrganizationAdminService {

    private final OrganizationRepository organizationRepository;
    private final OrganizationProvisioningService organizationProvisioningService;
    private final OrganizationUsageCounter usageCounter;
    private final Clock clock;

    @Override
    @Transactional(readOnly = true)
    public PageResponse<OrganizationView> list(PageRequest pageRequest) {
        var page = organizationRepository.findAll(PageAdapter.toSpringPageable(pageRequest));
        return PageAdapter.toPageResponse(page.map(DefaultOrganizationAdminService::toView));
    }

    @Override
    @Transactional(readOnly = true)
    public OrganizationView get(UUID organizationId) {
        return toView(load(organizationId));
    }

    @Override
    @Transactional
    public OrganizationView create(CreateOrganizationCommand command) {
        // Reuse the provisioning service's unique-slug logic; then apply quotas to the saved row.
        var id = organizationProvisioningService.create(command.name(), command.requestedSlug());
        var organization = load(id);
        organization.setMaxDatasources(command.maxDatasources());
        organization.setMaxUsers(command.maxUsers());
        organization.setMaxQueriesPerDay(command.maxQueriesPerDay());
        organization.setUpdatedAt(clock.instant());
        return toView(organization);
    }

    @Override
    @Transactional
    public OrganizationView update(UUID organizationId, UpdateOrganizationCommand command) {
        var organization = load(organizationId);
        if (command.name() != null) {
            organization.setName(command.name());
        }
        if (command.maxDatasources() != null) {
            organization.setMaxDatasources(command.maxDatasources());
        }
        if (command.maxUsers() != null) {
            organization.setMaxUsers(command.maxUsers());
        }
        if (command.maxQueriesPerDay() != null) {
            organization.setMaxQueriesPerDay(command.maxQueriesPerDay());
        }
        organization.setUpdatedAt(clock.instant());
        return toView(organization);
    }

    @Override
    @Transactional
    public OrganizationView setDisabled(UUID organizationId, boolean disabled) {
        var organization = load(organizationId);
        organization.setDisabled(disabled);
        organization.setUpdatedAt(clock.instant());
        return toView(organization);
    }

    @Override
    @Transactional(readOnly = true)
    public OrganizationUsageView getUsage(UUID organizationId) {
        var organization = load(organizationId);
        return new OrganizationUsageView(
                organizationId,
                usageCounter.datasourceCount(organizationId), organization.getMaxDatasources(),
                usageCounter.activeUserCount(organizationId), organization.getMaxUsers(),
                usageCounter.queriesLast24h(organizationId), organization.getMaxQueriesPerDay());
    }

    private OrganizationEntity load(UUID organizationId) {
        return organizationRepository.findById(organizationId)
                .orElseThrow(() -> new OrganizationNotFoundException(organizationId));
    }

    private static OrganizationView toView(OrganizationEntity entity) {
        return new OrganizationView(
                entity.getId(),
                entity.getName(),
                entity.getSlug(),
                entity.isDisabled(),
                entity.getMaxDatasources(),
                entity.getMaxUsers(),
                entity.getMaxQueriesPerDay(),
                entity.getCreatedAt(),
                entity.getUpdatedAt());
    }
}
