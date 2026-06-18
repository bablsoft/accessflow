package com.bablsoft.accessflow.core.internal;

import com.bablsoft.accessflow.core.api.CreateOrganizationCommand;
import com.bablsoft.accessflow.core.api.OrganizationNotFoundException;
import com.bablsoft.accessflow.core.api.OrganizationProvisioningService;
import com.bablsoft.accessflow.core.api.PageRequest;
import com.bablsoft.accessflow.core.api.UpdateOrganizationCommand;
import com.bablsoft.accessflow.core.internal.persistence.entity.OrganizationEntity;
import com.bablsoft.accessflow.core.internal.persistence.repo.OrganizationRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultOrganizationAdminServiceTest {

    @Mock OrganizationRepository organizationRepository;
    @Mock OrganizationProvisioningService provisioningService;
    @Mock OrganizationUsageCounter usageCounter;

    private final Clock clock = Clock.fixed(Instant.parse("2026-06-18T12:00:00Z"), ZoneOffset.UTC);
    private final UUID orgId = UUID.randomUUID();

    private DefaultOrganizationAdminService service() {
        return new DefaultOrganizationAdminService(organizationRepository, provisioningService,
                usageCounter, clock);
    }

    private OrganizationEntity org() {
        var e = new OrganizationEntity();
        e.setId(orgId);
        e.setName("Acme");
        e.setSlug("acme");
        e.setMaxDatasources(10);
        return e;
    }

    @Test
    void listMapsToViews() {
        when(organizationRepository.findAll(any(org.springframework.data.domain.Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(org())));

        var page = service().list(PageRequest.of(0, 20));

        assertThat(page.content()).hasSize(1);
        assertThat(page.content().get(0).name()).isEqualTo("Acme");
        assertThat(page.content().get(0).maxDatasources()).isEqualTo(10);
    }

    @Test
    void getThrowsWhenMissing() {
        when(organizationRepository.findById(orgId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service().get(orgId)).isInstanceOf(OrganizationNotFoundException.class);
    }

    @Test
    void createDelegatesSlugAndAppliesQuotas() {
        when(provisioningService.create("Acme", "acme")).thenReturn(orgId);
        when(organizationRepository.findById(orgId)).thenReturn(Optional.of(org()));

        var result = service().create(new CreateOrganizationCommand("Acme", "acme", 20, 50, 1000));

        assertThat(result.maxDatasources()).isEqualTo(20);
        assertThat(result.maxUsers()).isEqualTo(50);
        assertThat(result.maxQueriesPerDay()).isEqualTo(1000);
    }

    @Test
    void updateAppliesNonNullFieldsOnly() {
        var entity = org();
        when(organizationRepository.findById(orgId)).thenReturn(Optional.of(entity));

        var result = service().update(orgId, new UpdateOrganizationCommand("Renamed", null, 99, null));

        assertThat(result.name()).isEqualTo("Renamed");
        assertThat(result.maxDatasources()).isEqualTo(10); // unchanged (null skip)
        assertThat(result.maxUsers()).isEqualTo(99);
        assertThat(entity.getUpdatedAt()).isEqualTo(clock.instant());
    }

    @Test
    void setDisabledFlips() {
        var entity = org();
        when(organizationRepository.findById(orgId)).thenReturn(Optional.of(entity));

        var result = service().setDisabled(orgId, true);

        assertThat(result.disabled()).isTrue();
        assertThat(entity.isDisabled()).isTrue();
    }

    @Test
    void getUsageAssemblesCounts() {
        var entity = org();
        entity.setMaxUsers(50);
        entity.setMaxQueriesPerDay(1000);
        when(organizationRepository.findById(orgId)).thenReturn(Optional.of(entity));
        when(usageCounter.datasourceCount(orgId)).thenReturn(3L);
        when(usageCounter.activeUserCount(orgId)).thenReturn(7L);
        when(usageCounter.queriesLast24h(orgId)).thenReturn(42L);

        var usage = service().getUsage(orgId);

        assertThat(usage.datasourceCount()).isEqualTo(3L);
        assertThat(usage.maxDatasources()).isEqualTo(10);
        assertThat(usage.userCount()).isEqualTo(7L);
        assertThat(usage.maxUsers()).isEqualTo(50);
        assertThat(usage.queriesLast24h()).isEqualTo(42L);
        assertThat(usage.maxQueriesPerDay()).isEqualTo(1000);
    }
}
