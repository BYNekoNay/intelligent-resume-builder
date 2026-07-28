package com.intelligentresume.ai.ratelimit;

import com.intelligentresume.ai.task.domain.AiTaskType;
import com.intelligentresume.ai.task.repository.AiTaskRepository;
import com.intelligentresume.common.error.BusinessException;
import com.intelligentresume.common.observability.AppObservability;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiQuotaServiceTest {

    @Test
    void recordsQuotaRejectionBeforeReturningRateLimitedError() {
        AiTaskRepository repository = mock(AiTaskRepository.class);
        AppObservability observability = mock(AppObservability.class);
        AiQuotaService service = new AiQuotaService(repository, 1, 1, 1, 1, 1, 1, observability);
        when(repository.countAttemptsByUserIdAndTaskTypeAndCreatedAtAfter(eq(7L), eq(AiTaskType.JOB_GENERATION), any()))
                .thenReturn(1L);

        assertThrows(BusinessException.class, () -> service.check(7L, AiTaskType.JOB_GENERATION));

        verify(observability).recordQuotaRejected(AiTaskType.JOB_GENERATION);
    }
}
