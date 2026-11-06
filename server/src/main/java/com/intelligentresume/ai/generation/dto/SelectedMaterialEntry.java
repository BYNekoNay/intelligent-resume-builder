package com.intelligentresume.ai.generation.dto;

/**
 * 被选中的资料条目。outputPath 示例: "work[0].highlights[2]"。
 *
 * <p>一句人话：记录"草稿的哪个位置用了哪条资料"，是确认页展示来源、
 * 以及资料留存清扫判断"还有人用"的依据。
 *
 * <p>要点：
 * <ul>
 *   <li>{@code outputPath} 是草稿中的真实位置，由 {@code collectSources} 递归拼接（数组带下标）。</li>
 *   <li>{@code selectedReason} 目前固定为 {@code AI_REFERENCED} —— 表示"模型在输出里引用了它"，
 *       不是用户勾选的意思。</li>
 *   <li>只有能按 materialId 在本次候选表里查到的引用才会生成条目；模型编造的 ID 直接丢弃，
 *       否则资料留存清扫会误判"还有人用"而永远删不掉。</li>
 *   <li>同一资料在多个位置被引用会产生多条（materialId 相同、path 不同），写入前按整条记录去重。</li>
 * </ul>
 */
public record SelectedMaterialEntry(
        Long materialId,
        String outputPath,
        String selectedReason
) {
}
