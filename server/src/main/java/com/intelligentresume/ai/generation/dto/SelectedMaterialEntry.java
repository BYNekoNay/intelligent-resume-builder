package com.intelligentresume.ai.generation.dto;

/**
 * 被选中的资料条目。outputPath 示例: "work[0].highlights[2]"。
 */
public record SelectedMaterialEntry(
        Long materialId,
        String outputPath,
        String selectedReason
) {
}
