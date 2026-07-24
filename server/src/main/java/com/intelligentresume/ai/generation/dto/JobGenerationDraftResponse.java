package com.intelligentresume.ai.generation.dto;

import java.util.List;
import java.util.Map;

/**
 * 岗位定制生成草稿响应。存入 ai_task.result_json。
 */
public record JobGenerationDraftResponse(
        Long taskId,
        String status,
        Map<String, Object> draftResumeJson,
        List<SelectedMaterialEntry> selected,
        List<UnselectedMaterialEntry> unselected,
        List<MissingItem> missing,
        String promptVersion,
        String schemaVersion,
        List<String> warnings
) {
}
