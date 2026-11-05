package com.intelligentresume.careermaterial.dto;

import com.intelligentresume.careermaterial.domain.MaterialType;
import com.intelligentresume.careermaterial.domain.UsagePreference;

import java.time.LocalDateTime;

/**
 * 资料列表摘要行：资料库左栏卡片用的最小字段集。
 *
 * <p>要点：
 * <ul>
 *   <li>故意不含 {@code sourceText} 与 {@code contentJson}：列表按用户全量返回，
 *       带上 MEDIUMTEXT 会让"打开资料库"变成一次大字段全表读；</li>
 *   <li>{@code evidenceReady} 由 SQL 判原文非空 + Java 判结构化内容共同得出，
 *       与详情/搜索三条路径共用同一规则；</li>
 *   <li>顺序由仓储方法的 {@code order by} 决定（当前仅按 updatedAt 倒序），
 *       接口不保证并列时的稳定次序。</li>
 * </ul>
 */
public record CareerMaterialSummary(
        Long id,
        MaterialType materialType,
        String title,
        UsagePreference usagePreference,
        LocalDateTime updatedAt,
        boolean evidenceReady
) {
}
