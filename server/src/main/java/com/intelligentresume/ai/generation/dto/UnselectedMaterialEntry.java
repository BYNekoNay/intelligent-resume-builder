package com.intelligentresume.ai.generation.dto;

/**
 * 未被选中的资料条目。selectionStatus: NOT_SELECTED / USER_EXCLUDED。
 *
 * <p>要点：
 * <ul>
 *   <li><b>目前没有任何生产代码构造本 record</b>，它只描述契约形态。{@code result_json} 里
 *       {@code unselected} 键实际存的是（旧任务路径）{@code CareerMaterial} 列表或（快照路径）空列表 ——
 *       与本 record 的字段并不一致，改动任一侧都要先确认前端到底按哪种形状解析。</li>
 *   <li>{@code unselectedReason} 是原因码（如 {@code USER_EXCLUDED} / {@code INVALID_EVIDENCE} /
 *       {@code EXCEEDS_MAX_SELECTED}），不是展示文案。</li>
 *   <li>该字段只用于"解释为什么没用它"，不参与生成，缺失也不会让任务失败。</li>
 * </ul>
 */
public record UnselectedMaterialEntry(
        Long materialId,
        String unselectedReason,
        String selectionStatus
) {
}
