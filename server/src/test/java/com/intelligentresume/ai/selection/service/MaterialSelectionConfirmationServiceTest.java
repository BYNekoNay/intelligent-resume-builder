package com.intelligentresume.ai.selection.service;

import com.intelligentresume.ai.consent.service.AiConsentService;
import com.intelligentresume.ai.preview.AiPreviewGuard;
import com.intelligentresume.ai.ratelimit.AiQuotaService;
import com.intelligentresume.ai.selection.dto.ConfirmMaterialsRequest;
import com.intelligentresume.ai.task.domain.AiTask;
import com.intelligentresume.ai.task.domain.AiTaskStatus;
import com.intelligentresume.ai.task.domain.AiTaskType;
import com.intelligentresume.ai.task.domain.ConfirmationStatus;
import com.intelligentresume.ai.task.repository.AiTaskRepository;
import com.intelligentresume.ai.task.service.IdempotencyService;
import com.intelligentresume.careermaterial.domain.CareerMaterial;
import com.intelligentresume.careermaterial.domain.MaterialType;
import com.intelligentresume.careermaterial.domain.UsagePreference;
import com.intelligentresume.careermaterial.repository.CareerMaterialRepository;
import com.intelligentresume.common.error.BusinessException;
import com.intelligentresume.common.error.ErrorCode;
import com.intelligentresume.jobdescription.domain.JobDescription;
import com.intelligentresume.jobdescription.repository.JobDescriptionRepository;
import com.intelligentresume.personalprofile.repository.PersonalProfileRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MaterialSelectionConfirmationServiceTest {

    private final AiTaskRepository taskRepository = mock(AiTaskRepository.class);
    private final CareerMaterialRepository materialRepository = mock(CareerMaterialRepository.class);
    private final JobDescriptionRepository jobRepository = mock(JobDescriptionRepository.class);
    private final PersonalProfileRepository profileRepository = mock(PersonalProfileRepository.class);
    private final AiConsentService consentService = mock(AiConsentService.class);
    private final AiQuotaService quotaService = mock(AiQuotaService.class);
    private final com.intelligentresume.ai.generation.service.JobGenerationPromptBuilder promptBuilder =
            mock(com.intelligentresume.ai.generation.service.JobGenerationPromptBuilder.class);
    private final AiPreviewGuard previewGuard = mock(AiPreviewGuard.class);
    private com.intelligentresume.ai.generation.service.JobGenerationInputAssembler inputAssembler;
    private MaterialSelectionConfirmationService service;
    private AiTask selection;

    @BeforeEach
    void setUp() {
        inputAssembler = new com.intelligentresume.ai.generation.service.JobGenerationInputAssembler(
                promptBuilder, "v1.0.0");
        service = new MaterialSelectionConfirmationService(taskRepository, materialRepository,
                jobRepository, profileRepository, new IdempotencyService(), consentService, quotaService,
                inputAssembler, previewGuard);
        when(promptBuilder.build(any(), anyList(), anyList(), anyList(), any(Map.class), anyString()))
                .thenReturn(new com.intelligentresume.ai.generation.service.JobGenerationPromptBuilder.Prompt(
                        "sys", "task", "data"));
        LocalDateTime updatedAt = LocalDateTime.of(2026, 7, 26, 10, 0);
        selection = new AiTask();
        selection.setId(10L);
        selection.setUserId(7L);
        selection.setTaskType(AiTaskType.JOB_MATERIAL_SELECTION);
        selection.setStatus(AiTaskStatus.SUCCESS);
        selection.setConfirmationStatus(ConfirmationStatus.PENDING);
        selection.setUpdatedAt(updatedAt);
        selection.setInputSnapshotJson(Map.of(
                "jobDescriptionId", 8L,
                "input", Map.of("excludedMaterialIds", List.of(2L))));
        selection.setResultJson(Map.of(
                "recommended", List.of(Map.of("materialId", 1L)),
                "unselected", List.of(),
                "excluded", List.of(
                        Map.of("materialId", 2L, "exclusionReason", "MANUAL"),
                        Map.of("materialId", 3L, "exclusionReason", "GLOBAL"))));

        when(taskRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(selection));
        when(consentService.hasValidConsent(anyLong(), anyString(), anyCollection())).thenReturn(true);
        when(taskRepository.findByUserIdAndTaskTypeAndIdempotencyKey(anyLong(), any(), anyString()))
                .thenReturn(Optional.empty());
        JobDescription job = new JobDescription();
        job.setId(8L);
        job.setUserId(7L);
        job.setTitle("Java 后端工程师");
        job.setJdText("Java Spring Boot");
        when(jobRepository.findByIdAndUserId(8L, 7L)).thenReturn(Optional.of(job));
        when(profileRepository.findByUserId(7L)).thenReturn(Optional.empty());
        when(materialRepository.findByUserIdOrderByUpdatedAtDescIdDesc(7L)).thenReturn(List.of(
                material(1L, UsagePreference.NORMAL),
                material(2L, UsagePreference.NORMAL),
                material(3L, UsagePreference.EXCLUDED),
                material(4L, UsagePreference.NORMAL)));
    }

    @Test
    void rejectsOwnedMaterialThatWasNotInSelectionCandidates() {
        ConfirmMaterialsRequest request = new ConfirmMaterialsRequest(
                selection.getUpdatedAt(), List.of(4L), List.of(), "岗位简历");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.confirm(10L, request, "confirm-1", "token-1", 7L));

        assertEquals(ErrorCode.VALIDATION, ex.getErrorCode());
        verify(taskRepository, never()).save(any());
    }

    @Test
    void manualExclusionCannotBeForcedBackIntoGeneration() {
        ConfirmMaterialsRequest request = new ConfirmMaterialsRequest(
                selection.getUpdatedAt(), List.of(1L), List.of(2L), "岗位简历");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.confirm(10L, request, "confirm-2", "token-1", 7L));

        assertEquals(ErrorCode.VALIDATION, ex.getErrorCode());
        verify(taskRepository, never()).save(any());
    }

    @Test
    void carriesSelectionGapsIntoTheImmutableGenerationSnapshot() {
        selection.setResultJson(Map.of(
                "recommended", List.of(Map.of("materialId", 1L)),
                "unselected", List.of(),
                "excluded", List.of(),
                "missingRequirements", List.of("Kafka production experience")));
        doAnswer(invocation -> invocation.getArgument(0)).when(taskRepository).save(any(AiTask.class));
        ConfirmMaterialsRequest request = new ConfirmMaterialsRequest(
                selection.getUpdatedAt(), List.of(1L), List.of(), "Job resume");

        AiTask generation = service.confirm(10L, request, "confirm-3", "token-1", 7L);

        assertEquals(List.of("Kafka production experience"),
                generation.getInputSnapshotJson().get("missingRequirements"));
        // 配额闸门必须以与直接创建任务一致的形态被调用（P1-2 回归）
        verify(quotaService).check(7L, AiTaskType.JOB_GENERATION);
    }

    @Test
    void quotaExhaustedRejectsConfirmationWithoutCreatingChildOrConfirming() {
        // 配额耗尽构造方式与 AiTaskServiceTest.create_quotaExceeded_throwsRateLimited 一致：
        // AiQuotaService.check 超限时抛 RATE_LIMITED，确认选材必须原样传播该异常。
        doThrow(new BusinessException(ErrorCode.RATE_LIMITED, "AI 任务配额已用完"))
                .when(quotaService).check(7L, AiTaskType.JOB_GENERATION);
        ConfirmMaterialsRequest request = new ConfirmMaterialsRequest(
                selection.getUpdatedAt(), List.of(1L), List.of(), "岗位简历");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.confirm(10L, request, "confirm-quota", "token-1", 7L));

        assertEquals(ErrorCode.RATE_LIMITED, ex.getErrorCode());
        // 不能创建子任务
        verify(taskRepository, never()).save(any());
        // 不能把 selection 置为 CONFIRMED，否则用户卡死：选材已确认但无法生成
        assertEquals(ConfirmationStatus.PENDING, selection.getConfirmationStatus());
    }

    @Test
    @SuppressWarnings("unchecked")
    void verifiesPreviewTokenAgainstTheServerAssembledGenerationInput() {
        doAnswer(invocation -> invocation.getArgument(0)).when(taskRepository).save(any(AiTask.class));
        ConfirmMaterialsRequest request = new ConfirmMaterialsRequest(
                selection.getUpdatedAt(), List.of(1L), List.of(), "岗位简历");

        service.confirm(10L, request, "confirm-guard", "token-abc", 7L);

        // 令牌校验拿的是服务端自己组装的 providerInput（与 worker 同源），不是客户端传的内容
        org.mockito.ArgumentCaptor<Map<String, Object>> captured =
                org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(previewGuard).require(eq(AiTaskType.JOB_GENERATION), captured.capture(), eq("token-abc"));
        assertEquals("sys", captured.getValue().get("_systemPrompt"));
        assertEquals("task", captured.getValue().get("_taskPrompt"));
        assertEquals("data", captured.getValue().get("_dataPrompt"));
    }

    @Test
    void idempotentReplayReturnsExistingChildWithoutAskingForTheTokenAgain() {
        doAnswer(invocation -> invocation.getArgument(0)).when(taskRepository).save(any(AiTask.class));
        ConfirmMaterialsRequest request = new ConfirmMaterialsRequest(
                selection.getUpdatedAt(), List.of(1L), List.of(), "岗位简历");
        AiTask first = service.confirm(10L, request, "confirm-replay", "token-1", 7L);

        // 客户端重试：同一个 Idempotency-Key，但这次**没有**带回令牌（例如刷新后重放）
        when(taskRepository.findByUserIdAndTaskTypeAndIdempotencyKey(
                7L, AiTaskType.JOB_GENERATION, "confirm-replay")).thenReturn(Optional.of(first));
        org.mockito.Mockito.clearInvocations(previewGuard);

        AiTask replay = service.confirm(10L, request, "confirm-replay", null, 7L);

        // 幂等语义优先于令牌校验：重试不该因为缺令牌而炸，也不该重复创建/重复扣配额
        org.junit.jupiter.api.Assertions.assertSame(first, replay);
        verify(previewGuard, never()).require(any(), any(), any());
        verify(quotaService, org.mockito.Mockito.times(1)).check(7L, AiTaskType.JOB_GENERATION);
    }

    private CareerMaterial material(Long id, UsagePreference preference) {
        CareerMaterial material = new CareerMaterial();
        material.setId(id);
        material.setUserId(7L);
        material.setTitle("Material " + id);
        material.setMaterialType(MaterialType.WORK_EXPERIENCE);
        material.setUsagePreference(preference);
        material.setSourceText("source");
        material.setContentJson(Map.of("description", "source"));
        return material;
    }
}
