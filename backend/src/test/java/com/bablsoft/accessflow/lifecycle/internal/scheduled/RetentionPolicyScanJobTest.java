package com.bablsoft.accessflow.lifecycle.internal.scheduled;

import com.bablsoft.accessflow.lifecycle.internal.RetentionPolicyScanService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class RetentionPolicyScanJobTest {

    @Mock
    private RetentionPolicyScanService scanService;

    @InjectMocks
    private RetentionPolicyScanJob job;

    @Test
    void run_delegatesToScanService() {
        job.run();
        verify(scanService).scanAndStage();
    }
}
