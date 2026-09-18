package com.bablsoft.accessflow.serviceaccounts.internal;

import com.bablsoft.accessflow.serviceaccounts.api.ServiceAccountSource;
import com.bablsoft.accessflow.serviceaccounts.internal.persistence.entity.ServiceAccountEntity;
import com.bablsoft.accessflow.serviceaccounts.internal.persistence.repo.ServiceAccountRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultServiceAccountToolPolicyServiceTest {

    @Mock ServiceAccountRepository repository;
    @InjectMocks DefaultServiceAccountToolPolicyService service;

    private final UUID userId = UUID.randomUUID();

    @Test
    void unknownToolNameIsDeniedBeforeAnyLookup() {
        assertThat(service.isAllowed(userId, "drop_database")).isFalse();
        assertThat(service.isAllowed(userId, "SUBMIT_QUERY")).isFalse();
        assertThat(service.isAllowed(userId, null)).isFalse();
        verifyNoInteractions(repository);
    }

    @Test
    void humanWithoutDetailRowIsAllowedEverything() {
        when(repository.findById(userId)).thenReturn(Optional.empty());
        assertThat(service.isAllowed(userId, "submit_query")).isTrue();
    }

    @Test
    void nullAllowListAllowsEveryTool() {
        when(repository.findById(userId)).thenReturn(Optional.of(account(null)));
        assertThat(service.isAllowed(userId, "submit_query")).isTrue();
        assertThat(service.isAllowed(userId, "review_query")).isTrue();
    }

    @Test
    void emptyAllowListAllowsNothing() {
        when(repository.findById(userId)).thenReturn(Optional.of(account(new String[0])));
        assertThat(service.isAllowed(userId, "list_datasources")).isFalse();
    }

    @Test
    void explicitAllowListAllowsOnlyItsEntries() {
        when(repository.findById(userId)).thenReturn(Optional.of(
                account(new String[] {"list_datasources", "validate_sql"})));
        assertThat(service.isAllowed(userId, "validate_sql")).isTrue();
        assertThat(service.isAllowed(userId, "submit_query")).isFalse();
    }

    private ServiceAccountEntity account(String[] allowList) {
        var entity = new ServiceAccountEntity();
        entity.setUserId(userId);
        entity.setOrganizationId(UUID.randomUUID());
        entity.setManagedBy(ServiceAccountSource.UI);
        entity.setMcpToolAllowList(allowList);
        return entity;
    }
}
