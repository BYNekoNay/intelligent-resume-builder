package com.intelligentresume.ai.worker;

import com.intelligentresume.ai.consent.service.AiConsentService;
import com.intelligentresume.ai.generation.service.JobGenerationService;
import com.intelligentresume.ai.selection.service.JobMaterialSelectionService;
import com.intelligentresume.ai.provider.AiCallContext;
import com.intelligentresume.ai.provider.AiCallResult;
import com.intelligentresume.ai.provider.AiProvider;
import com.intelligentresume.ai.provider.AiProviderRegistry;
import com.intelligentresume.ai.task.domain.AiTask;
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
        TaskExecutionService service = new TaskExecutionService(
                new AiProviderRegistry(List.of(provider)), leaseService, mock(JobGenerationService.class),
                mock(JobMaterialSelectionService.class), consentService, mock(AppObservability.class),
                new FailureCategoryClassifier());
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
    }
}
