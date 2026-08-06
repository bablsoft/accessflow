package com.bablsoft.accessflow.ai.internal.scheduled;

import com.bablsoft.accessflow.ai.api.ApprovalPredictionService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * The job is a thin driver, so these tests cover exactly what it owns: delegation and batch-level
 * error containment. Per-organization isolation — one org failing without stopping the others — is
 * {@code trainAll()}'s contract and is covered by {@code DefaultApprovalPredictionServiceTest}.
 */
class ApprovalPredictionTrainingJobTest {

    private final ApprovalPredictionService approvalPredictionService =
            mock(ApprovalPredictionService.class);
    private final ApprovalPredictionTrainingJob job =
            new ApprovalPredictionTrainingJob(approvalPredictionService);

    @Test
    void runDelegatesToTrainAll() {
        job.run();

        verify(approvalPredictionService).trainAll();
    }

    @Test
    void runSwallowsServiceException() {
        doThrow(new IllegalStateException("boom")).when(approvalPredictionService).trainAll();

        assertThatCode(job::run).doesNotThrowAnyException();

        verify(approvalPredictionService, times(1)).trainAll();
    }
}
