package com.intelligentresume.careermaterial.dto;

import com.intelligentresume.careermaterial.domain.MaterialType;
import com.intelligentresume.careermaterial.domain.UsagePreference;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 资料详情响应：详情页/编辑器的完整视图。
 *
 * <p>要点：
 * <ul>
 *   <li>唯一携带 {@code sourceText} 全量原文的 DTO；列表与搜索刻意不返回它（MEDIUMTEXT 太贵）；</li>
 *   <li>{@code evidenceReady} 由服务端算好下发，前端不要用 {@code title}/{@code contentJson}
 *       自行判定"资料是否可用"——判定规则在 {@code CareerMaterialEvidence}，两端各写一套必然漂移；</li>
 *   <li>{@code contentJson} 直接透传库里的 JSON，没有按类型裁剪，字段语义由前端按
 *       {@code materialType} 解释；</li>
 *   <li>创建/更新/查询三个入口返回的都是本对象，字段增减即改前端契约。</li>
 * </ul>
 */
public record CareerMaterialDetail(
        Long id,
        MaterialType materialType,
        String title,
        Map<String, Object> contentJson,
        String sourceText,
        UsagePreference usagePreference,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        boolean evidenceReady
) {}
