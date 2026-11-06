package com.intelligentresume.ai.selection.dto;

import java.util.List;

/**
 * 发起选材 / 选材预览的请求体（POST /api/ai/select-materials-for-job 与其 /preview）。
 *
 * <p>一句人话：用户点"让 AI 帮我挑资料"时提交的内容——岗位（已保存的 ID 或直接粘贴的 JD 原文）
 * 加三类资料勾选（必须用/优先用/排除）。
 *
 * <p>要点：
 * <ul>
 *   <li>岗位二选一：给 {@code jobDescriptionId} 用已保存的岗位；否则用 {@code jdText} 现建一条
 *       临时岗位（{@code positionTitle} 为空时标题固定写 {@code Temporary job}）。
 *       <b>两个都给时以 {@code jobDescriptionId} 为准</b>，{@code jdText} 被忽略。</li>
 *   <li>两个都不给 → 400。注意本 record **没有任何字段级约束**，
 *       "二选一"完全靠服务端手写校验，控制器上的 {@code @Valid} 对这里不起作用。</li>
 *   <li>三个素材 ID 列表都允许为 null；服务端把 null 归一成空列表后写入任务快照，
 *       后续 {@code MaterialSelectionContextBuilder} 只认快照里的这三个键。</li>
 *   <li>临时岗位只在**确认发起**时落库（预览绝不写库），预览与正式发起必须用同一份字段值，
 *       否则两次组装的 prompt 不同、预览令牌校验不过。</li>
 * </ul>
 */
public record SelectMaterialsRequest(
        Long jobDescriptionId,
        String jdText,
        String companyName,
        String positionTitle,
        List<Long> includedMaterialIds,
        List<Long> preferredMaterialIds,
        List<Long> excludedMaterialIds,
        String resumeTitle
) {
}
