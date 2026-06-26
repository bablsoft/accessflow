package com.bablsoft.accessflow.attestation.internal.scheduled;

import com.bablsoft.accessflow.attestation.api.AttestationLifecycleService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AttestationCampaignJobsTest {

    @Mock AttestationLifecycleService lifecycleService;

    @Test
    void openJobOpensEveryDueCampaign() {
        var a = UUID.randomUUID();
        var b = UUID.randomUUID();
        when(lifecycleService.findCampaignIdsDueToOpen(any())).thenReturn(List.of(a, b));
        new AttestationCampaignOpenJob(lifecycleService).run();
        verify(lifecycleService).openCampaign(a);
        verify(lifecycleService).openCampaign(b);
    }

    @Test
    void openJobContinuesAfterPerCampaignFailure() {
        var a = UUID.randomUUID();
        var b = UUID.randomUUID();
        when(lifecycleService.findCampaignIdsDueToOpen(any())).thenReturn(List.of(a, b));
        when(lifecycleService.openCampaign(a)).thenThrow(new RuntimeException("boom"));
        new AttestationCampaignOpenJob(lifecycleService).run();
        verify(lifecycleService).openCampaign(b);
    }

    @Test
    void openJobNoOpWhenNothingDue() {
        when(lifecycleService.findCampaignIdsDueToOpen(any())).thenReturn(List.of());
        new AttestationCampaignOpenJob(lifecycleService).run();
        verify(lifecycleService, never()).openCampaign(any());
    }

    @Test
    void closeJobClosesEveryDueCampaign() {
        var a = UUID.randomUUID();
        when(lifecycleService.findCampaignIdsDueToClose(any())).thenReturn(List.of(a));
        new AttestationCampaignCloseJob(lifecycleService).run();
        verify(lifecycleService).closeCampaign(a);
    }

    @Test
    void closeJobContinuesAfterPerCampaignFailure() {
        var a = UUID.randomUUID();
        var b = UUID.randomUUID();
        when(lifecycleService.findCampaignIdsDueToClose(any())).thenReturn(List.of(a, b));
        when(lifecycleService.closeCampaign(a)).thenThrow(new RuntimeException("boom"));
        new AttestationCampaignCloseJob(lifecycleService).run();
        verify(lifecycleService).closeCampaign(b);
    }
}
