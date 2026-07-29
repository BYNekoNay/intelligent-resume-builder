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
import com.intelligentresume.ats.service.AtsAiAnalysisService;
import com.intelligentresume.ats.service.AtsResultStateService;
import com.intelligentresume.ats.service.AtsAiAnalysisException;
import com.intelligentresume.ats.dto.AtsAiInsights;
import com.intelligentresume.ats.dto.AtsFallbackCode;
import com.intelligentresume.communication.service.CommunicationAiService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class TaskExecutionServiceTest {

    @Test
    void dispatchesCommunicationGenerationToItsValidatedService() {
        TaskLeaseService leaseService = mock(TaskLeaseService.class);
        AiConsentService consentService = mock(AiConsentService.class);
        CommunicationAiService communicationService = mock(CommunicationAiService.class);
        when(consentService.hasValidConsent(1L, "COMMUNICATION_GENERATE",
                List.of("RESUME", "JOB_DESCRIPTION"))).thenReturn(true);
        when(communicationService.executeTask(any())).thenReturn(
                new CommunicationAiService.ExecutionResult(Map.of(
                        "generationSource", "AI", "draft", "Validated communication draft")));
        when(leaseService.releaseSuccess(any(), eq("worker-1"), any())).thenAnswer(invocation -> {
            AiTask completed = invocation.getArgument(0);
            completed.setStatus(AiTaskStatus.SUCCESS);
            return true;
        });
        TaskExecutionService service = new TaskExecutionService(
                mock(AiProviderRegistry.class), leaseService, mock(JobGenerationService.class),
                mock(JobMaterialSelectionService.class), consentService, mock(AppObservability.class),
                new FailureCategoryClassifier(), new InlineOptimizeResultFormatter(),
                mock(AtsAiAnalysisService.class), mock(AtsResultStateService.class), communicationService,
                new AiTaskWorkerProperties());
        AiTask task = new AiTask();
        task.setId(4L);
        task.setUserId(1L);
        task.setTaskType(AiTaskType.COMMUNICATION_GENERATE);
        task.setInputSnapshotJson(Map.of("input", Map.of("type", "COVER_LETTER")));

        service.execute(task, "worker-1");

        verify(communicationService).executeTask(task);
        verify(leaseService).releaseSuccess(task, "worker-1",
                Map.of("generationSource", "AI", "draft", "Validated communication draft"));
        service.shutdownHeartbeatExecutor();
    }

    @Test
    void persistsHybridAtsInsightsOnlyAfterTheTaskLeaseCompletes() {
        TaskLeaseService leaseService = mock(TaskLeaseService.class);
        AiConsentService consentService = mock(AiConsentService.class);
        AtsAiAnalysisService analysisService = mock(AtsAiAnalysisService.class);
        AtsResultStateService stateService = mock(AtsResultStateService.class);
        AtsAiInsights insights = new AtsAiInsights("summary", List.of(), List.of(), List.of(), List.of(), "MEDIUM");
        when(consentService.hasValidConsent(1L, "ATS_ANALYSIS", List.of("RESUME", "JOB_DESCRIPTION"))).thenReturn(true);
        when(analysisService.analyze(any())).thenReturn(
                new AtsAiAnalysisService.AnalysisResult(insights, Map.of("aiInsights", Map.of("summary", "summary"))));
        when(leaseService.releaseSuccess(any(), eq("worker-1"), any())).thenAnswer(invocation -> {
            AiTask completed = invocation.getArgument(0);
            completed.setStatus(AiTaskStatus.SUCCESS);
            return true;
        });
        TaskExecutionService service = new TaskExecutionService(
                mock(AiProviderRegistry.class), leaseService, mock(JobGenerationService.class),
                mock(JobMaterialSelectionService.class), consentService, mock(AppObservability.class),
                new FailureCategoryClassifier(), new InlineOptimizeResultFormatter(), analysisService, stateService,
                mock(CommunicationAiService.class),
                new AiTaskWorkerProperties());
        AiTask task = atsTask();

        service.execute(task, "worker-1");

        verify(stateService).markCompleted(9L, 1L, 3L, insights);
        service.shutdownHeartbeatExecutor();
    }

    @Test
    void persistsRulesFallbackWhenAtsOutputIsInvalid() {
        TaskLeaseService leaseService = mock(TaskLeaseService.class);
        AiConsentService consentService = mock(AiConsentService.class);
        AtsAiAnalysisService analysisService = mock(AtsAiAnalysisService.class);
        AtsResultStateService stateService = mock(AtsResultStateService.class);
        when(consentService.hasValidConsent(1L, "ATS_ANALYSIS", List.of("RESUME", "JOB_DESCRIPTION"))).thenReturn(true);
        when(analysisService.analyze(any())).thenThrow(
                new AtsAiAnalysisException(AtsFallbackCode.INVALID_RESPONSE, "invalid schema", false));
        when(leaseService.releaseFailed(any(), eq("worker-1"), eq("invalid schema"), eq(false))).thenAnswer(invocation -> {
            AiTask failed = invocation.getArgument(0);
            failed.setStatus(AiTaskStatus.FAILED);
            return true;
        });
        TaskExecutionService service = new TaskExecutionService(
                mock(AiProviderRegistry.class), leaseService, mock(JobGenerationService.class),
                mock(JobMaterialSelectionService.class), consentService, mock(AppObservability.class),
                new FailureCategoryClassifier(), new InlineOptimizeResultFormatter(), analysisService, stateService,
                mock(CommunicationAiService.class),
                new AiTaskWorkerProperties());
        AiTask task = atsTask();

        service.execute(task, "worker-1");

        verify(stateService).markFallback(eq(9L), eq(1L), eq(3L), eq(AtsFallbackCode.INVALID_RESPONSE), anyString(), eq(false), eq(false));
        service.shutdownHeartbeatExecutor();
    }

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
                new FailureCategoryClassifier(), new InlineOptimizeResultFormatter(),
                mock(AtsAiAnalysisService.class), mock(AtsResultStateService.class),
                mock(CommunicationAiService.class), new AiTaskWorkerProperties());
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
                new FailureCategoryClassifier(), new InlineOptimizeResultFormatter(),
                mock(AtsAiAnalysisService.class), mock(AtsResultStateService.class),
                mock(CommunicationAiService.class), properties);
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

    private AiTask atsTask() {
        AiTask task = new AiTask();
        task.setId(3L);
        task.setUserId(1L);
        task.setTaskType(AiTaskType.ATS_ANALYSIS);
        task.setInputSnapshotJson(Map.of("taskType", "ATS_ANALYSIS", "input", Map.of("atsCheckResultId", 9L)));
        return task;
    }
}
