package com.intelligentresume.ai.worker;

import com.intelligentresume.ai.consent.service.AiConsentService;
import com.intelligentresume.ai.generation.service.JobGenerationService;
import com.intelligentresume.ai.optimize.service.InlineOptimizeResultFormatter;
import com.intelligentresume.ai.selection.service.JobMaterialSelectionService;
import com.intelligentresume.ai.provider.AiCallContext;
import com.intelligentresume.ai.provider.AiCallResult;
import com.intelligentresume.ai.provider.AiProvider;
import com.intelligentresume.ai.provider.AiProviderRegistry;
import com.intelligentresume.ai.task.domain.AiTask;
import com.intelligentresume.ai.task.domain.AiTaskStatus;
import com.intelligentresume.ai.task.domain.AiTaskType;
import com.intelligentresume.common.observability.AppObservability;
import com.intelligentresume.common.observability.FailureCategoryClassifier;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class TaskExecutionServiceTest {

    @Test
    void passesTheActualTaskInputToTheProvider() {
        AiProvider provider = mock(AiProvider.class);
        TaskLeaseService leaseService = mock(TaskLeaseService.class);
        AiConsentService consentService = mock(AiConsentService.class);
        when(provider.supports(AiTaskType.MATERIAL_IMPORT)).thenReturn(true);
        when(provider.call(any())).thenReturn(AiCallResult.ok(Map.of("expandedMaterial", "reference"), "request-1"));
        when(leaseService.releaseSuccess(any(), eq("worker-1"), any())).thenAnswer(invocation -> {
            AiTask completed = invocation.getArgument(0);
            completed.setStatus(AiTaskStatus.SUCCESS);
            return true;
        });
        TaskExecutionService service = new TaskExecutionService(
                new AiProviderRegistry(List.of(provider)), leaseService, mock(JobGenerationService.class),
                mock(JobMaterialSelectionService.class), consentService, mock(AppObservability.class),
                new FailureCategoryClassifier(), new InlineOptimizeResultFormatter(), new AiTaskWorkerProperties());
        AiTask task = new AiTask();
        task.setId(1L);
        task.setUserId(1L);
        task.setTaskType(AiTaskType.MATERIAL_IMPORT);
        task.setInputSnapshotJson(Map.of(
                "taskType", "MATERIAL_IMPORT",
                "input", Map.of("generationMode", "ASSOCIATIVE_EXPANSION", "rawMaterialText", "microservices")));
        when(consentService.hasValidConsent(1L, "MATERIAL_IMPORT", List.of())).thenReturn(true);

        service.execute(task, "worker-1");

        ArgumentCaptor<AiCallContext> context = ArgumentCaptor.forClass(AiCallContext.class);
        verify(provider).call(context.capture());
        assertEquals("ASSOCIATIVE_EXPANSION", context.getValue().input().get("generationMode"));
        assertEquals("microservices", context.getValue().input().get("rawMaterialText"));
        service.shutdownHeartbeatExecutor();
    }

    @Test
    void renewsTheLeaseWhileAProviderCallIsStillRunning() {
        AiProvider provider = mock(AiProvider.class);
        TaskLeaseService leaseService = mock(TaskLeaseService.class);
        AiConsentService consentService = mock(AiConsentService.class);
        AiTaskWorkerProperties properties = new AiTaskWorkerProperties();
        properties.setLeaseSeconds(1);
        when(provider.supports(AiTaskType.MATERIAL_IMPORT)).thenReturn(true);
        when(provider.call(any())).thenAnswer(invocation -> {
            Thread.sleep(1_300);
            return AiCallResult.ok(Map.of("output", "done"), "request-2");
        });
        when(leaseService.renew(2L, "worker-1")).thenReturn(true);
        when(leaseService.releaseSuccess(any(), eq("worker-1"), any())).thenAnswer(invocation -> {
            AiTask completed = invocation.getArgument(0);
            completed.setStatus(AiTaskStatus.SUCCESS);
            return true;
        });
        TaskExecutionService service = new TaskExecutionService(
                new AiProviderRegistry(List.of(provider)), leaseService, mock(JobGenerationService.class),
                mock(JobMaterialSelectionService.class), consentService, mock(AppObservability.class),
                new FailureCategoryClassifier(), new InlineOptimizeResultFormatter(), properties);
        AiTask task = new AiTask();
        task.setId(2L);
        task.setUserId(1L);
        task.setTaskType(AiTaskType.MATERIAL_IMPORT);
        task.setInputSnapshotJson(Map.of("taskType", "MATERIAL_IMPORT", "input", Map.of("content", "test")));
        when(consentService.hasValidConsent(1L, "MATERIAL_IMPORT", List.of())).thenReturn(true);

        service.execute(task, "worker-1");

        verify(leaseService, atLeastOnce()).renew(2L, "worker-1");
        verify(leaseService).releaseSuccess(eq(task), eq("worker-1"), any());
        service.shutdownHeartbeatExecutor();
    }
}
