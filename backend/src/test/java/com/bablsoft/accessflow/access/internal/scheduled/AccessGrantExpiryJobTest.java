package com.bablsoft.accessflow.access.internal.scheduled;

import com.bablsoft.accessflow.access.api.AccessGrantExpiryService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AccessGrantExpiryJobTest {

    @Mock AccessGrantExpiryService accessGrantExpiryService;
    @InjectMocks AccessGrantExpiryJob job;

    @Test
    void runDoesNothingWhenNoneExpired() {
        when(accessGrantExpiryService.findExpiredGrantedIds(any())).thenReturn(List.of());
        job.run();
        verify(accessGrantExpiryService, never()).expireAndRevoke(any());
    }

    @Test
    void runProcessesEachIdAndIsolatesFailures() {
        var ok1 = UUID.randomUUID();
        var bad = UUID.randomUUID();
        var ok2 = UUID.randomUUID();
        when(accessGrantExpiryService.findExpiredGrantedIds(any())).thenReturn(List.of(ok1, bad, ok2));
        when(accessGrantExpiryService.expireAndRevoke(ok1)).thenReturn(true);
        when(accessGrantExpiryService.expireAndRevoke(bad)).thenThrow(new RuntimeException("boom"));
        when(accessGrantExpiryService.expireAndRevoke(ok2)).thenReturn(true);

        job.run();

        verify(accessGrantExpiryService).expireAndRevoke(ok1);
        verify(accessGrantExpiryService).expireAndRevoke(bad);
        verify(accessGrantExpiryService).expireAndRevoke(ok2);
    }
}
