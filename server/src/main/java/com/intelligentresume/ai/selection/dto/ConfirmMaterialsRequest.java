package com.intelligentresume.ai.selection.dto;

import jakarta.validation.constraints.NotNull;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 确认选材请求体（POST /api/ai/tasks/{taskId}/confirm-materials），预览端点用的是同一个 body。
 *
 * <p>一句人话：用户在推荐结果页勾完资料点"确认"时提交的内容——勾了哪些、
 * 有没有强制加入、以及页面停留期间的并发保护戳。
 *
 * <p>要点：
 * <ul>
 *   <li>{@code taskUpdatedAt} 是乐观并发戳：必须回传查询选材任务时拿到的 {@code updatedAt}，
 *       与库里不一致就返回 409（说明期间任务被改过，要求刷新）。</li>
 *   <li>{@code selectedMaterialIds} 是常规勾选，必须来自本轮推荐/未选候选；
 *       {@code forcedIncludedMaterialIds} 是"强制加入"，只允许被全局排除的资料。</li>
 *   <li>最终素材集合 = 两者并集，1..30 条，为空或超限都返回 400。</li>
 *   <li>{@code resumeTitle} 可空：空时沿用选材任务里的原标题。</li>
 * </ul>
 */
public record ConfirmMaterialsRequest(
        @NotNull LocalDateTime taskUpdatedAt,
        @NotNull List<Long> selectedMaterialIds,
        List<Long> forcedIncludedMaterialIds,
        String resumeTitle
) {
}
