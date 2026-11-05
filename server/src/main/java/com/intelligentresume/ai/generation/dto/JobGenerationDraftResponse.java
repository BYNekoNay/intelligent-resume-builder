package com.intelligentresume.ai.generation.dto;

import java.util.List;
import java.util.Map;

/**
 * 岗位定制生成草稿响应。存入 ai_task.result_json。
 *
 * <p>要点：
 * <ul>
 *   <li>本 record 目前**没有生产代码构造它**，服务端是直接把一个 {@code LinkedHashMap} 写进
 *       {@code result_json}。所以它只是该 JSON 的形状说明，改字段名时要同步改
 *       {@code JobGenerationService} 里的 {@code result.put(...)} 键名。</li>
 *   <li>{@code draftResumeJson} 是**已经过 schema 校验、别名归一，并用服务端个人档案补写联系方式**
 *       的最终草稿；模型原始输出不会落在这里。</li>
 *   <li>{@code selected} 只记录真实引用到的资料（幻觉 materialId 在收集阶段就被过滤掉），
 *       资料留存清扫依赖这份记录判断"还有人用"。</li>
 *   <li>{@code missing} 由草稿里缺哪些板块推导而来，不是模型自报。</li>
 *   <li>{@code qualitySummary} 也在同一个 result_json 里，但不在本 record 字段中。</li>
 * </ul>
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
