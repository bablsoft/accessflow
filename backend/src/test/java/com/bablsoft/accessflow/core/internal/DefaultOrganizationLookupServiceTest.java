package com.bablsoft.accessflow.core.internal;

import com.bablsoft.accessflow.core.api.SingleOrganizationUnavailableException;
import com.bablsoft.accessflow.core.internal.persistence.entity.OrganizationEntity;
import com.bablsoft.accessflow.core.internal.persistence.repo.OrganizationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.MessageSource;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultOrganizationLookupServiceTest {

    @Mock OrganizationRepository organizationRepository;
    @Mock MessageSource messageSource;

    private DefaultOrganizationLookupService service;

    @BeforeEach
    void setUp() {
        service = new DefaultOrganizationLookupService(organizationRepository, messageSource);
        lenient().when(messageSource.getMessage(anyString(), any(), any())).thenReturn("msg");
    }

    @Test
    void returnsIdWhenExactlyOneOrgExists() {
        var org = new OrganizationEntity();
        var id = UUID.randomUUID();
        org.setId(id);
        when(organizationRepository.findAll()).thenReturn(List.of(org));

        assertThat(service.singleOrganization()).isEqualTo(id);
    }

    @Test
    void throwsWhenNoOrgs() {
        when(organizationRepository.findAll()).thenReturn(List.of());

        assertThatThrownBy(service::singleOrganization)
                .isInstanceOf(SingleOrganizationUnavailableException.class);
    }

    @Test
    void throwsWhenMoreThanOneOrg() {
        var a = new OrganizationEntity();
        a.setId(UUID.randomUUID());
        var b = new OrganizationEntity();
        b.setId(UUID.randomUUID());
        when(organizationRepository.findAll()).thenReturn(List.of(a, b));

        assertThatThrownBy(service::singleOrganization)
                .isInstanceOf(SingleOrganizationUnavailableException.class);
    }
}
