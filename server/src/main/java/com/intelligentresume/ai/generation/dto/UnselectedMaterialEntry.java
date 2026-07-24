package com.intelligentresume.ai.generation.dto;

/**
 * 未被选中的资料条目。selectionStatus: NOT_SELECTED / USER_EXCLUDED。
 */
public record UnselectedMaterialEntry(
        Long materialId,
        String unselectedReason,
        String selectionStatus
) {
}
