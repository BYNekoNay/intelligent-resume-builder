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
 * 选材溯源准入校验的用例（ADR-013 / D4 / D5）。
 *
 * <p>从 {@code DraftCommitServiceTest} 拆出：主类已超过 test 侧行数上限（400 行棘轮），
 * 且本组用例共享同一组 fixture（selected 与 items 的覆盖关系），拆到这里读起来更连贯。
 *
 * <p>三个用例是**成对设计**的：缺项（40904）与用户拒绝（合法跳过）都"不写引用"，
 * 若只留一个，回归时两者会互相冒充；未知原因（40001）则钉住"不静默兜底"。
 */
@ExtendWith(MockitoExtension.class)
class DraftCommitServiceSelectionTest {

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

    private AiTask buildTask() {
        AiTask task = new AiTask();
        task.setId(TASK_ID);
        task.setUserId(USER_ID);
        task.setTaskType(AiTaskType.JOB_GENERATION);
        task.setStatus(AiTaskStatus.SUCCESS);
        task.setConfirmationStatus(ConfirmationStatus.PENDING);
        task.setUpdatedAt(UPDATED_AT);
        task.setInputSnapshotJson(Map.of("targetResumeId", 10));

        Map<String, Object> draft = new LinkedHashMap<>();
        draft.put("basics", new LinkedHashMap<>(Map.of("name", "张三")));
        draft.put("work", List.of(new LinkedHashMap<>(Map.of("company", "ABC"))));

        Map<String, Object> resultJson = new LinkedHashMap<>();
        resultJson.put("draftResumeJson", draft);
        resultJson.put("selected", List.of(
                Map.of("materialId", 1, "outputPath", "work[0]", "selectedReason", "AI_REFERENCED")));
        task.setResultJson(resultJson);
        return task;
    }

    @Test
    @DisplayName("selected 有、items 无 → 40904 且零写入（ADR-013，替代原静默跳过语义）")
    void commit_selectedMaterialMissingFromItems_conflict() {
        // fixture：selected 两条（work[0] 与 basics），items 只覆盖 basics。
        // 历史语义（ADR-011 Decision 3）是静默跳过 work[0]；实测后果是「版本已落地但少了
        // 溯源引用」且无任何信号 —— 客户端少提交就会被当成正常成功。
        // v0.5.0 起改为显式拒绝（ADR-013）：40904，且必须在**任何写库之前**失败。
        AiTask task = buildTask();
        task.setResultJson(new LinkedHashMap<>(Map.of(
                "draftResumeJson", task.getResultJson().get("draftResumeJson"),
                "selected", List.of(
                        Map.of("materialId", 1, "outputPath", "work[0]", "selectedReason", "AI_REFERENCED"),
                        Map.of("materialId", 2, "outputPath", "basics", "selectedReason", "AI_REFERENCED")))));
        when(taskRepository.findByIdForUpdate(TASK_ID)).thenReturn(Optional.of(task));

        List<ConfirmedDraftItem> items = List.of(
                new ConfirmedDraftItem("basics", Decision.ACCEPT, null)
        );

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.commit(TASK_ID, items, null, UPDATED_AT, USER_ID, null, null));
        assertEquals(ErrorCode.SELECTION_ITEM_UNKNOWN, ex.getErrorCode());

        // 零写入：版本、引用、任务状态三者都不得变化（否则又是"半落地"）
        verify(versionService, never()).createInTransaction(anyLong(), any(), any(), any(), any(), anyLong());
        verify(referenceRepository, never()).save(any());
        assertEquals(ConfirmationStatus.PENDING, task.getConfirmationStatus());
        // 也不应读取被跳过的素材快照（校验发生在任何 IO 之前）
        verify(materialRepository, never()).findByIdAndUserId(anyLong(), anyLong());
    }

    @Test
    @DisplayName("decision != ACCEPT 仍是合法拒绝：不报 40904，且不写引用、不读快照")
    void commit_rejectedSelectedMaterial_isStillSkipped() {
        // 与上一个用例的差分：这里 work[0] **存在于 items**，只是用户选了 REJECT。
        // 二者都必须"不写引用"，但一个报 40904、一个正常提交 —— 混淆这两者会让
        // 正常的用户拒绝也变成错误，所以本用例与上例成对存在。
        AiTask task = buildTask();
        task.setResultJson(new LinkedHashMap<>(Map.of(
                "draftResumeJson", task.getResultJson().get("draftResumeJson"),
                "selected", List.of(
                        Map.of("materialId", 1, "outputPath", "work[0]", "selectedReason", "AI_REFERENCED"),
                        Map.of("materialId", 2, "outputPath", "basics", "selectedReason", "AI_REFERENCED")))));
        when(taskRepository.findByIdForUpdate(TASK_ID)).thenReturn(Optional.of(task));
        when(resumeRepository.findByIdAndUserId(10L, USER_ID))
                .thenReturn(Optional.of(new com.intelligentresume.resume.domain.Resume()));
        when(normalizer.normalize(any(), any())).thenReturn(new LinkedHashMap<>());
        ResumeVersion version = new ResumeVersion();
        version.setId(53L);
        version.setVersionNo(6);
        when(versionService.createInTransaction(anyLong(), any(), any(), any(), any(), anyLong()))
                .thenReturn(version);

        CareerMaterial coveredMaterial = new CareerMaterial();
        coveredMaterial.setId(2L);
        coveredMaterial.setTitle("基本信息素材");
        coveredMaterial.setMaterialType(MaterialType.EDUCATION);
        coveredMaterial.setContentJson(Map.of("name", "张三"));
        coveredMaterial.setUsagePreference(UsagePreference.NORMAL);
        when(materialRepository.findByIdAndUserId(2L, USER_ID)).thenReturn(Optional.of(coveredMaterial));

        List<ConfirmedDraftItem> items = List.of(
                new ConfirmedDraftItem("basics", Decision.ACCEPT, null),
                new ConfirmedDraftItem("work[0]", Decision.REJECT, null)
        );

        DraftCommitService.CommitResult result = assertDoesNotThrow(
                () -> service.commit(TASK_ID, items, null, UPDATED_AT, USER_ID, null, null));
        assertEquals(53L, result.resumeVersionId());
        assertEquals(ConfirmationStatus.CONFIRMED, task.getConfirmationStatus());

        // 恰好 1 条引用：被拒绝的 work[0] 不写，被 ACCEPT 的 basics 照常写
        ArgumentCaptor<ResumeMaterialReference> captor = ArgumentCaptor.forClass(ResumeMaterialReference.class);
        verify(referenceRepository, times(1)).save(captor.capture());
        assertEquals(2L, captor.getValue().getMaterialId(), "引用应指向被 ACCEPT 覆盖的素材 2");
        assertEquals("basics", captor.getValue().getOutputPath());
        verify(materialRepository, never()).findByIdAndUserId(1L, USER_ID);
    }

    @Test
    @DisplayName("未知 selection_reason 取值 → 40001 拒绝，且在任何写库/读快照之前失败（D5）")
    void commit_unknownSelectionReason_validationFailed() {
        AiTask task = buildTask();
        task.setResultJson(new LinkedHashMap<>(Map.of(
                "draftResumeJson", task.getResultJson().get("draftResumeJson"),
                "selected", List.of(
                        Map.of("materialId", 1, "outputPath", "work[0]", "selectedReason", "GHOST_REASON")))));
        when(taskRepository.findByIdForUpdate(TASK_ID)).thenReturn(Optional.of(task));

        List<ConfirmedDraftItem> items = List.of(
                new ConfirmedDraftItem("basics", Decision.ACCEPT, null),
                new ConfirmedDraftItem("work[0]", Decision.ACCEPT, null)
        );

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.commit(TASK_ID, items, null, UPDATED_AT, USER_ID, null, null));
        assertEquals(ErrorCode.VALIDATION, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("GHOST_REASON"), "错误信息应指出具体取值，便于排查: " + ex.getMessage());

        // 校验在写入之前：版本、引用、素材快照读取三者均未发生
        verify(versionService, never()).createInTransaction(anyLong(), any(), any(), any(), any(), anyLong());
        verify(referenceRepository, never()).save(any());
        verify(materialRepository, never()).findByIdAndUserId(anyLong(), anyLong());
        assertEquals(ConfirmationStatus.PENDING, task.getConfirmationStatus());
    }
}
