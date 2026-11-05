package com.intelligentresume.careermaterial.dto;

import com.intelligentresume.careermaterial.domain.MaterialType;
import com.intelligentresume.careermaterial.domain.UsagePreference;

import java.time.LocalDateTime;

/**
 * 搜索结果单条：只够渲染一行卡片，不含原文也不含完整 contentJson。
 *
 * <p>要点：
 * <ul>
 *   <li>{@code excerpt} 是服务端算的摘要片段：优先截取 sourceText，无原文时按资料类型
 *       从 contentJson 挑字段拼接，最长 180 字符；前端不要二次截断成不一致的长度；</li>
 *   <li>excerpt 对未在分支里列出的类型恒为空串，这是既有取舍，不是"没找到内容"；</li>
 *   <li>{@code evidenceReady} 与详情页同源（{@code CareerMaterialEvidence}），
 *       用于列表上标"资料不足"；</li>
 *   <li>要展示完整正文必须再调 GET /{id}，本对象刻意不带 contentJson。</li>
 * </ul>
 */
public record CareerMaterialSearchItem(
        Long id,
        MaterialType materialType,
        String title,
        UsagePreference usagePreference,
        LocalDateTime updatedAt,
        String excerpt,
        boolean evidenceReady
) {}
