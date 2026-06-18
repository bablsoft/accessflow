package com.bablsoft.accessflow.core.internal;

import com.bablsoft.accessflow.core.api.OrganizationNotFoundException;
import com.bablsoft.accessflow.core.api.QuotaExceededException;
import com.bablsoft.accessflow.core.internal.persistence.entity.OrganizationEntity;
import com.bablsoft.accessflow.core.internal.persistence.repo.OrganizationRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultQuotaServiceTest {

    @Mock OrganizationRepository organizationRepository;
    @Mock OrganizationUsageCounter usageCounter;
    @InjectMocks DefaultQuotaService service;

    private final UUID orgId = UUID.randomUUID();

    private OrganizationEntity org(Integer maxDs, Integer maxUsers, Integer maxQueries) {
        var e = new OrganizationEntity();
        e.setId(orgId);
        e.setMaxDatasources(maxDs);
        e.setMaxUsers(maxUsers);
        e.setMaxQueriesPerDay(maxQueries);
        return e;
    }

    @Test
    void datasourceUnderLimitPasses() {
        when(organizationRepository.findById(orgId)).thenReturn(Optional.of(org(5, null, null)));
        when(usageCounter.datasourceCount(orgId)).thenReturn(4L);

        assertThatCode(() -> service.checkDatasourceQuota(orgId)).doesNotThrowAnyException();
    }

    @Test
    void datasourceAtLimitThrows() {
        when(organizationRepository.findById(orgId)).thenReturn(Optional.of(org(5, null, null)));
        when(usageCounter.datasourceCount(orgId)).thenReturn(5L);

        assertThatThrownBy(() -> service.checkDatasourceQuota(orgId))
                .isInstanceOf(QuotaExceededException.class);
    }

    @Test
    void nullLimitIsUnlimited() {
        when(organizationRepository.findById(orgId)).thenReturn(Optional.of(org(null, null, null)));

        assertThatCode(() -> service.checkDatasourceQuota(orgId)).doesNotThrowAnyException();
    }

    @Test
    void zeroLimitIsUnlimited() {
        when(organizationRepository.findById(orgId)).thenReturn(Optional.of(org(null, 0, null)));

        assertThatCode(() -> service.checkUserQuota(orgId)).doesNotThrowAnyException();
    }

    @Test
    void userAtLimitThrows() {
        when(organizationRepository.findById(orgId)).thenReturn(Optional.of(org(null, 10, null)));
        when(usageCounter.activeUserCount(orgId)).thenReturn(10L);

        assertThatThrownBy(() -> service.checkUserQuota(orgId))
                .isInstanceOf(QuotaExceededException.class);
    }

    @Test
    void queryAtLimitThrows() {
        when(organizationRepository.findById(orgId)).thenReturn(Optional.of(org(null, null, 100)));
        when(usageCounter.queriesLast24h(orgId)).thenReturn(100L);

        assertThatThrownBy(() -> service.checkQueryQuota(orgId))
                .isInstanceOf(QuotaExceededException.class);
    }

    @Test
    void unknownOrganizationThrows() {
        when(organizationRepository.findById(orgId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.checkUserQuota(orgId))
                .isInstanceOf(OrganizationNotFoundException.class);
    }
}
