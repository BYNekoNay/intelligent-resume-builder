package com.intelligentresume.ai.confirmation.service;

import com.intelligentresume.ai.confirmation.dto.ConfirmedDraftItem;
import com.intelligentresume.ai.confirmation.dto.ConfirmedDraftItem.Decision;
import com.intelligentresume.ai.confirmation.domain.ResumeMaterialReference;
import com.intelligentresume.ai.confirmation.repository.ResumeMaterialReferenceRepository;
import com.intelligentresume.ai.generation.service.SourcePathResolver;
import com.intelligentresume.ai.task.domain.AiTask;
import com.intelligentresume.ai.task.domain.AiTaskStatus;
import com.intelligentresume.ai.task.domain.AiTaskType;
import com.intelligentresume.ai.task.domain.ConfirmationStatus;
import com.intelligentresume.ai.task.repository.AiTaskRepository;
import com.intelligentresume.careermaterial.domain.CareerMaterial;
import com.intelligentresume.careermaterial.domain.MaterialType;
import com.intelligentresume.careermaterial.domain.UsagePreference;
import com.intelligentresume.careermaterial.repository.CareerMaterialRepository;
import com.intelligentresume.common.error.BusinessException;
import com.intelligentresume.common.error.ErrorCode;
import com.intelligentresume.resume.domain.ResumeSourceType;
import com.intelligentresume.resume.domain.ResumeVersion;
import com.intelligentresume.resume.service.ResumeVersionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * DraftCommitService 单元测试（Mockito）。
 * 覆盖：原子提交、编辑新增资料、跨用户、乐观锁、状态校验、幂等、无效路径。
 */
@ExtendWith(MockitoExtension.class)
class DraftCommitServiceTest {

    @Mock private AiTaskRepository taskRepository;
    @Mock private ResumeVersionService versionService;
    @Mock private ResumeJsonNormalizer normalizer;
    @Mock private CareerMaterialRepository materialRepository;
    @Mock private ResumeMaterialReferenceRepository referenceRepository;
    @Mock private com.intelligentresume.resume.repository.ResumeRepository resumeRepository;

    private DraftCommitService service;
    private final SourcePathResolver pathResolver = new SourcePathResolver();

    private static final Long TASK_ID = 1L;
    private static final Long USER_ID = 100L;
    private static final LocalDateTime UPDATED_AT = LocalDateTime.of(2026, 7, 20, 10, 30, 0);

    @BeforeEach
    void setUp() {
        service = new DraftCommitService(
                taskRepository, versionService, normalizer,
                materialRepository, referenceRepository, pathResolver, resumeRepository);
    }

    private AiTask buildTask(AiTaskStatus status, ConfirmationStatus confStatus) {
        AiTask task = new AiTask();
        task.setId(TASK_ID);
        task.setUserId(USER_ID);
        task.setTaskType(AiTaskType.JOB_GENERATION);
        task.setStatus(status);
        task.setConfirmationStatus(confStatus);
        task.setUpdatedAt(UPDATED_AT);
        task.setInputSnapshotJson(Map.of("targetResumeId", 10));

        Map<String, Object> draft = new LinkedHashMap<>();
        draft.put("basics", new LinkedHashMap<>(Map.of("name", "张三")));
        draft.put("work", List.of(new LinkedHashMap<>(Map.of("company", "ABC"))));

        Map<String, Object> resultJson = new LinkedHashMap<>();
        resultJson.put("draftResumeJson", draft);
        resultJson.put("selected", List.of(
                Map.of("materialId", 1, "outputPath", "work[0]", "selectedReason", "AI_REFERENCED")
        ));
        task.setResultJson(resultJson);
        return task;
    }

    @Test
    @DisplayName("正常路径: 单事务创建 resume_version + reference,任务 CONFIRMED")
    void commit_createsVersionAndReferenceAtomically() {
        AiTask task = buildTask(AiTaskStatus.SUCCESS, ConfirmationStatus.PENDING);
        when(taskRepository.findByIdForUpdate(TASK_ID)).thenReturn(Optional.of(task));
        when(resumeRepository.findByIdAndUserId(10L, USER_ID))
                .thenReturn(Optional.of(new com.intelligentresume.resume.domain.Resume()));

        Map<String, Object> normalizedJson = Map.of("basics", Map.of("name", "张三"));
        when(normalizer.normalize(any(), any())).thenReturn(new LinkedHashMap<>(normalizedJson));

        CareerMaterial mat = new CareerMaterial();
        mat.setId(1L);
        mat.setTitle("Java开发");
        mat.setMaterialType(MaterialType.WORK_EXPERIENCE);
        mat.setContentJson(Map.of("company", "ABC"));
        mat.setUsagePreference(UsagePreference.NORMAL);
        when(materialRepository.findByIdAndUserId(1L, USER_ID)).thenReturn(Optional.of(mat));

        ResumeVersion version = new ResumeVersion();
        version.setId(50L);
        version.setVersionNo(3);
        when(versionService.createInTransaction(eq(10L), eq(ResumeSourceType.JD_CUSTOMIZED),
                any(), any(), any(), eq(USER_ID))).thenReturn(version);

        List<ConfirmedDraftItem> items = List.of(
                new ConfirmedDraftItem("basics", Decision.ACCEPT, null),
                new ConfirmedDraftItem("work[0]", Decision.ACCEPT, null)
        );

        DraftCommitService.CommitResult result =
                service.commit(TASK_ID, items, null, UPDATED_AT, USER_ID, null, null);

        assertEquals(50L, result.resumeVersionId());
        assertEquals(3, result.versionNo());
        assertTrue(result.newMaterialIds().isEmpty());

        // 验证 reference 被写入
        verify(referenceRepository).save(any());
        // 验证任务状态更新
        assertEquals(ConfirmationStatus.CONFIRMED, task.getConfirmationStatus());
        assertEquals(50L, task.getResultResumeVersionId());
        verify(taskRepository).save(task);
    }

    @Test
    @DisplayName("正常路径: 编辑超出资料范围时写入 career_material 并返回 newMaterialId")
    void commit_editsBeyondSource_createsNewMaterial() {
        AiTask task = buildTask(AiTaskStatus.SUCCESS, ConfirmationStatus.PENDING);
        when(taskRepository.findByIdForUpdate(TASK_ID)).thenReturn(Optional.of(task));
        when(resumeRepository.findByIdAndUserId(10L, USER_ID))
                .thenReturn(Optional.of(new com.intelligentresume.resume.domain.Resume()));
        when(normalizer.normalize(any(), any())).thenReturn(new LinkedHashMap<>());

        when(materialRepository.save(any(CareerMaterial.class))).thenAnswer(inv -> {
            CareerMaterial m = inv.getArgument(0);
            m.setId(99L);
            return m;
        });
        CareerMaterial editedMaterial = new CareerMaterial();
        editedMaterial.setId(99L);
        editedMaterial.setUserId(USER_ID);
        editedMaterial.setTitle("用户确认编辑");
        editedMaterial.setMaterialType(MaterialType.WORK_EXPERIENCE);
        editedMaterial.setContentJson(Map.of("company", "新公司", "role", "架构师"));
        editedMaterial.setUsagePreference(UsagePreference.NORMAL);
        when(materialRepository.findByIdAndUserId(99L, USER_ID)).thenReturn(Optional.of(editedMaterial));

        ResumeVersion version = new ResumeVersion();
        version.setId(51L);
        version.setVersionNo(4);
        when(versionService.createInTransaction(anyLong(), any(), any(), any(), any(), anyLong()))
                .thenReturn(version);

        List<ConfirmedDraftItem> items = List.of(
                new ConfirmedDraftItem("work[0]", Decision.EDIT,
                        Map.of("company", "新公司", "role", "架构师"))
        );

        DraftCommitService.CommitResult result =
                service.commit(TASK_ID, items, null, UPDATED_AT, USER_ID, null, null);

        assertTrue(result.newMaterialIds().contains(99L));

        ArgumentCaptor<CareerMaterial> captor = ArgumentCaptor.forClass(CareerMaterial.class);
        verify(materialRepository).save(captor.capture());
        assertEquals(USER_ID, captor.getValue().getUserId());
        assertEquals(MaterialType.WORK_EXPERIENCE, captor.getValue().getMaterialType());
    }

    @Test
    @DisplayName("引用只对应最终采纳内容，编辑来源标为用户编辑，拒绝内容不产生引用")
    void commit_referencesOnlyAcceptedAndEditedFinalContent() {
        AiTask task = buildTask(AiTaskStatus.SUCCESS, ConfirmationStatus.PENDING);
        Map<String, Object> first = new LinkedHashMap<>();
        first.put("company", "Accepted Co");
        first.put("_sources", List.of(Map.of("materialId", 1, "materialType", "WORK_EXPERIENCE")));
        Map<String, Object> second = new LinkedHashMap<>();
        second.put("company", "AI Draft Co");
        second.put("_sources", List.of(Map.of("materialId", 2, "materialType", "WORK_EXPERIENCE")));
        Map<String, Object> third = new LinkedHashMap<>();
        third.put("company", "Rejected Co");
        third.put("_sources", List.of(Map.of("materialId", 3, "materialType", "WORK_EXPERIENCE")));
        Map<String, Object> draft = new LinkedHashMap<>();
        draft.put("work", List.of(first, second, third));
        task.setResultJson(new LinkedHashMap<>(Map.of(
                "draftResumeJson", draft,
                "selected", List.of(
                        Map.of("materialId", 1, "outputPath", "work[0]", "selectedReason", "AI_REFERENCED"),
                        Map.of("materialId", 2, "outputPath", "work[1]", "selectedReason", "AI_REFERENCED"),
                        Map.of("materialId", 3, "outputPath", "work[2]", "selectedReason", "AI_REFERENCED")
                ))));
        when(taskRepository.findByIdForUpdate(TASK_ID)).thenReturn(Optional.of(task));
        when(resumeRepository.findByIdAndUserId(10L, USER_ID))
                .thenReturn(Optional.of(new com.intelligentresume.resume.domain.Resume()));
        when(normalizer.normalize(any(), any())).thenAnswer(invocation ->
                new ResumeJsonNormalizer().normalize(invocation.getArgument(0), invocation.getArgument(1)));

        CareerMaterial acceptedMaterial = new CareerMaterial();
        acceptedMaterial.setId(1L);
        acceptedMaterial.setTitle("Accepted source");
        acceptedMaterial.setMaterialType(MaterialType.WORK_EXPERIENCE);
        acceptedMaterial.setContentJson(Map.of("company", "Accepted Co"));
        acceptedMaterial.setUsagePreference(UsagePreference.NORMAL);
        when(materialRepository.findByIdAndUserId(1L, USER_ID)).thenReturn(Optional.of(acceptedMaterial));
        when(materialRepository.save(any(CareerMaterial.class))).thenAnswer(invocation -> {
            CareerMaterial material = invocation.getArgument(0);
            material.setId(99L);
            return material;
        });
        when(materialRepository.findByIdAndUserId(99L, USER_ID)).thenAnswer(invocation -> {
            CareerMaterial material = new CareerMaterial();
            material.setId(99L);
            material.setUserId(USER_ID);
            material.setTitle("用户确认编辑");
            material.setMaterialType(MaterialType.WORK_EXPERIENCE);
            material.setContentJson(Map.of("company", "User Edited Co", "role", "Architect"));
            material.setSourceText("{company=User Edited Co, role=Architect}");
            material.setUsagePreference(UsagePreference.NORMAL);
            return Optional.of(material);
        });
        ResumeVersion version = new ResumeVersion();
        version.setId(52L);
        version.setVersionNo(5);
        when(versionService.createInTransaction(anyLong(), any(), any(), any(), any(), anyLong()))
                .thenReturn(version);

        List<ConfirmedDraftItem> items = List.of(
                new ConfirmedDraftItem("work[0]", Decision.ACCEPT, null),
                new ConfirmedDraftItem("work[1]", Decision.EDIT, Map.of(
                        "company", "User Edited Co", "role", "Architect",
                        "_sources", List.of(Map.of("materialId", 2, "materialType", "WORK_EXPERIENCE")))),
                new ConfirmedDraftItem("work[2]", Decision.REJECT, null)
        );

        service.commit(TASK_ID, items, null, UPDATED_AT, USER_ID, null, null);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> resumeJsonCaptor = ArgumentCaptor.forClass(Map.class);
        verify(versionService).createInTransaction(eq(10L), eq(ResumeSourceType.JD_CUSTOMIZED),
                resumeJsonCaptor.capture(), any(), any(), eq(USER_ID));
        List<Map<String, Object>> finalWork = (List<Map<String, Object>>) resumeJsonCaptor.getValue().get("work");
        assertEquals(2, finalWork.size());
        assertEquals("Accepted Co", finalWork.get(0).get("company"));
        assertEquals("User Edited Co", finalWork.get(1).get("company"));

        ArgumentCaptor<ResumeMaterialReference> referenceCaptor =
                ArgumentCaptor.forClass(ResumeMaterialReference.class);
        verify(referenceRepository, times(2)).save(referenceCaptor.capture());
        List<ResumeMaterialReference> references = referenceCaptor.getAllValues();
        assertEquals(Set.of(1L, 99L), references.stream()
                .map(ResumeMaterialReference::getMaterialId).collect(java.util.stream.Collectors.toSet()));
        assertEquals(Set.of("work[0]", "work[1]"), references.stream()
                .map(ResumeMaterialReference::getOutputPath).collect(java.util.stream.Collectors.toSet()));

        ResumeMaterialReference editedReference = references.stream()
                .filter(reference -> reference.getMaterialId().equals(99L)).findFirst().orElseThrow();
        assertEquals("USER_EDITED", editedReference.getSelectionReason());
        assertEquals("USER_EDITED", editedReference.getSourceSnapshotJson().get("origin"));
        Map<String, Object> editedSnapshotContent = (Map<String, Object>)
                editedReference.getSourceSnapshotJson().get("contentJson");
        assertEquals("User Edited Co", editedSnapshotContent.get("company"));
        assertFalse(editedSnapshotContent.containsKey("_sources"));
        verify(materialRepository, never()).findByIdAndUserId(2L, USER_ID);
        verify(materialRepository, never()).findByIdAndUserId(3L, USER_ID);
    }

    @Test
    @DisplayName("失败路径: 跨用户 confirm 抛 NOT_FOUND")
    void commit_crossUser_notFound() {
        AiTask task = buildTask(AiTaskStatus.SUCCESS, ConfirmationStatus.PENDING);
        when(taskRepository.findByIdForUpdate(TASK_ID)).thenReturn(Optional.of(task));

        Long otherUser = 999L;
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.commit(TASK_ID, List.of(), null, UPDATED_AT, otherUser, null, null));
        assertEquals(ErrorCode.NOT_FOUND.code(), ex.getErrorCode().code());
    }

    @Test
    @DisplayName("失败路径: taskUpdatedAt 不匹配抛 40901")
    void commit_staleTaskUpdatedAt_conflict() {
        AiTask task = buildTask(AiTaskStatus.SUCCESS, ConfirmationStatus.PENDING);
        when(taskRepository.findByIdForUpdate(TASK_ID)).thenReturn(Optional.of(task));

        LocalDateTime staleTime = UPDATED_AT.minusHours(1);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.commit(TASK_ID, List.of(), null, staleTime, USER_ID, null, null));
        assertEquals(ErrorCode.CONFLICT.code(), ex.getErrorCode().code());
    }

    @Test
    @DisplayName("失败路径: 任务状态非 SUCCESS 抛 40901")
    void commit_wrongTaskStatus_conflict() {
        AiTask task = buildTask(AiTaskStatus.RUNNING, ConfirmationStatus.PENDING);
        when(taskRepository.findByIdForUpdate(TASK_ID)).thenReturn(Optional.of(task));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.commit(TASK_ID, List.of(), null, UPDATED_AT, USER_ID, null, null));
        assertEquals(ErrorCode.CONFLICT.code(), ex.getErrorCode().code());
    }

    @Test
    @DisplayName("失败路径: 已 CONFIRMED 的任务再次 confirm 抛 40901")
    void commit_alreadyConfirmed_idempotentOrConflict() {
        AiTask task = buildTask(AiTaskStatus.SUCCESS, ConfirmationStatus.CONFIRMED);
        when(taskRepository.findByIdForUpdate(TASK_ID)).thenReturn(Optional.of(task));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.commit(TASK_ID, List.of(), null, UPDATED_AT, USER_ID, null, null));
        assertEquals(ErrorCode.CONFLICT.code(), ex.getErrorCode().code());
    }

    @Test
    @DisplayName("失败路径: sourcePath 在草稿中不存在抛 VALIDATION")
    void commit_invalidPath_throws() {
        AiTask task = buildTask(AiTaskStatus.SUCCESS, ConfirmationStatus.PENDING);
        when(taskRepository.findByIdForUpdate(TASK_ID)).thenReturn(Optional.of(task));

        List<ConfirmedDraftItem> items = List.of(
                new ConfirmedDraftItem("nonexistent[0].field", Decision.ACCEPT, null)
        );

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.commit(TASK_ID, items, null, UPDATED_AT, USER_ID, null, null));
        assertEquals(ErrorCode.VALIDATION.code(), ex.getErrorCode().code());
    }
}
