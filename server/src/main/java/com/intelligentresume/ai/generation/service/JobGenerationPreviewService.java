package com.intelligentresume.ai.generation.service;

import com.intelligentresume.ai.preview.AiPreviewService;
import com.intelligentresume.ai.selection.dto.ConfirmMaterialsRequest;
import com.intelligentresume.ai.selection.service.MaterialSelectionConfirmationService;
import com.intelligentresume.ai.task.domain.AiTask;
import com.intelligentresume.ai.task.domain.AiTaskStatus;
import com.intelligentresume.ai.task.domain.AiTaskType;
import com.intelligentresume.ai.task.repository.AiTaskRepository;
import com.intelligentresume.common.error.BusinessException;
import com.intelligentresume.common.error.ErrorCode;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * JOB_GENERATION 的发送前预览（R32 AC-R32-8 / AC-R32-12）：返回**将要发送的确切 messages** 与令牌。
 *
 * <p>三步都只读，且每一步都与 confirm / worker 共用实现，因此"预览内容 == 实际发送内容"不是靠约定：
 * <ol>
 *   <li>{@link MaterialSelectionConfirmationService#buildGenerationInput} 组装子任务快照（confirm 也调它，
 *       落库的就是这一份）；</li>
 *   <li>{@link JobGenerationInputAssembler#fromSnapshot} 由快照得到 provider input（worker 也调它）；</li>
 *   <li>{@link AiPreviewService#preview} 由 provider input 算出 messages 与令牌（provider 也走同一组装器）。</li>
 * </ol>
 *
 * <p>预览**不做**的事：不落库、不调 provider、不消耗配额、不改 selection 的确认状态。
 */
@Service
public class JobGenerationPreviewService {

    private final AiTaskRepository taskRepository;
    private final MaterialSelectionConfirmationService confirmationService;
    private final JobGenerationInputAssembler inputAssembler;
    private final AiPreviewService previewService;

    public JobGenerationPreviewService(AiTaskRepository taskRepository,
                                       MaterialSelectionConfirmationService confirmationService,
                                       JobGenerationInputAssembler inputAssembler,
                                       AiPreviewService previewService) {
        this.taskRepository = taskRepository;
        this.confirmationService = confirmationService;
        this.inputAssembler = inputAssembler;
        this.previewService = previewService;
    }

    public AiPreviewService.PreviewResponse preview(Long selectionTaskId, ConfirmMaterialsRequest request, Long userId) {
        AiTask selection = taskRepository.findById(selectionTaskId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Selection task not found"));
        if (!selection.getUserId().equals(userId) || selection.getTaskType() != AiTaskType.JOB_MATERIAL_SELECTION) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "Selection task not found");
        }
        if (selection.getStatus() != AiTaskStatus.SUCCESS) {
            throw new BusinessException(ErrorCode.CONFLICT,
                    "Selection task is not finished yet; preview is available after it succeeds");
        }
        Map<String, Object> snapshot = confirmationService.buildGenerationInput(selection, request, userId);
        Map<String, Object> providerInput = inputAssembler.fromSnapshot(snapshot).providerInput();
        return previewService.preview(new AiPreviewService.PreviewRequest(
                AiTaskType.JOB_GENERATION, providerInput, false));
    }
}
