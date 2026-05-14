package com.bablsoft.accessflow.core.internal;

import com.bablsoft.accessflow.core.internal.persistence.entity.OrganizationEntity;
import com.bablsoft.accessflow.core.internal.persistence.repo.OrganizationRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultOrganizationProvisioningServiceTest {

    @Mock OrganizationRepository organizationRepository;
    @InjectMocks DefaultOrganizationProvisioningService service;

    @Test
    void findBySlugReturnsEmptyForBlankInput() {
        assertThat(service.findBySlug(null)).isEmpty();
        assertThat(service.findBySlug("")).isEmpty();
        assertThat(service.findBySlug("   ")).isEmpty();
    }

    @Test
    void findBySlugReturnsOrgIdWhenPresent() {
        var orgId = UUID.randomUUID();
        var entity = new OrganizationEntity();
        entity.setId(orgId);
        when(organizationRepository.findBySlug("acme")).thenReturn(Optional.of(entity));

        assertThat(service.findBySlug("acme")).contains(orgId);
    }

    @Test
    void createUsesProvidedSlugVerbatimWhenAvailable() {
        when(organizationRepository.existsBySlug("acme")).thenReturn(false);
        when(organizationRepository.save(any(OrganizationEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        var id = service.create("Acme Corp", "acme");
        assertThat(id).isNotNull();

        var captor = ArgumentCaptor.forClass(OrganizationEntity.class);
        org.mockito.Mockito.verify(organizationRepository).save(captor.capture());
        assertThat(captor.getValue().getSlug()).isEqualTo("acme");
        assertThat(captor.getValue().getName()).isEqualTo("Acme Corp");
    }

    @Test
    void createDerivesSlugFromNameWhenRequestedIsBlank() {
        when(organizationRepository.existsBySlug("widgets-co")).thenReturn(false);
        when(organizationRepository.save(any(OrganizationEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        service.create("Widgets & Co!", "");

        var captor = ArgumentCaptor.forClass(OrganizationEntity.class);
        org.mockito.Mockito.verify(organizationRepository).save(captor.capture());
        assertThat(captor.getValue().getSlug()).isEqualTo("widgets-co");
    }

    @Test
    void createAppendsSuffixOnSlugCollision() {
        when(organizationRepository.existsBySlug("acme")).thenReturn(true);
        when(organizationRepository.existsBySlug(startsWith("acme-"))).thenReturn(false);
        when(organizationRepository.save(any(OrganizationEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        service.create("Acme", null);

        var captor = ArgumentCaptor.forClass(OrganizationEntity.class);
        org.mockito.Mockito.verify(organizationRepository).save(captor.capture());
        assertThat(captor.getValue().getSlug()).startsWith("acme-");
    }
}
