package com.bablsoft.accessflow.core.internal.scheduled;

import com.bablsoft.accessflow.core.internal.config.DataBudgetProperties;
import com.bablsoft.accessflow.core.internal.persistence.repo.DataBudgetUsageRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DataBudgetUsagePruneJobTest {

    private static final Instant NOW = Instant.parse("2026-09-25T12:00:00Z");

    @Mock DataBudgetUsageRepository usageRepository;

    @Test
    void deletesRowsOlderThanTheRetention() {
        var job = new DataBudgetUsagePruneJob(usageRepository,
                new DataBudgetProperties(Duration.ofDays(40)), Clock.fixed(NOW, ZoneOffset.UTC));
        var cutoff = NOW.minus(Duration.ofDays(40));
        when(usageRepository.deleteOlderThan(cutoff)).thenReturn(3);

        job.run();

        verify(usageRepository).deleteOlderThan(cutoff);
    }

    @Test
    void nothingToPruneIsQuiet() {
        var job = new DataBudgetUsagePruneJob(usageRepository, new DataBudgetProperties(null),
                Clock.fixed(NOW, ZoneOffset.UTC));
        var cutoff = NOW.minus(DataBudgetProperties.DEFAULT_RETENTION);
        when(usageRepository.deleteOlderThan(cutoff)).thenReturn(0);

        job.run();

        verify(usageRepository).deleteOlderThan(cutoff);
    }
}
