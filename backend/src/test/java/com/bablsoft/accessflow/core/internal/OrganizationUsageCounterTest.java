package com.bablsoft.accessflow.core.internal;

import com.bablsoft.accessflow.core.internal.persistence.repo.DatasourceRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.QueryRequestRepository;
import com.bablsoft.accessflow.core.internal.persistence.repo.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrganizationUsageCounterTest {

    @Mock DatasourceRepository datasourceRepository;
    @Mock UserRepository userRepository;
    @Mock QueryRequestRepository queryRequestRepository;

    private final Instant now = Instant.parse("2026-06-18T12:00:00Z");
    private final Clock clock = Clock.fixed(now, ZoneOffset.UTC);
    private final UUID orgId = UUID.randomUUID();

    private OrganizationUsageCounter counter() {
        return new OrganizationUsageCounter(datasourceRepository, userRepository,
                queryRequestRepository, clock);
    }

    @Test
    void datasourceCountDelegates() {
        when(datasourceRepository.countByOrganization_Id(orgId)).thenReturn(3L);
        assertThat(counter().datasourceCount(orgId)).isEqualTo(3L);
    }

    @Test
    void activeUserCountDelegates() {
        when(userRepository.countByOrganization_IdAndActiveTrue(orgId)).thenReturn(7L);
        assertThat(counter().activeUserCount(orgId)).isEqualTo(7L);
    }

    @Test
    void queriesLast24hUsesTrailingWindow() {
        var since = now.minus(OrganizationUsageCounter.QUERY_WINDOW);
        when(queryRequestRepository.countByOrganizationSince(eq(orgId), eq(since))).thenReturn(42L);

        assertThat(counter().queriesLast24h(orgId)).isEqualTo(42L);
    }
}
