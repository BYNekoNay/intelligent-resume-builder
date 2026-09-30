package com.intelligentresume.careermaterial.dto;

import com.intelligentresume.careermaterial.domain.MaterialType;
import com.intelligentresume.careermaterial.domain.UsagePreference;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 资料列表读模型行（ideation #1）。
 *
 * <p>列表只需要摘要字段与「证据是否就绪」；本行对象避免把 MEDIUMTEXT 原文整体
 * 读回应用层，并用 {@code hasSourceText} 承载 SQL 侧的原文非空判定，
 * {@code evidenceReady} 仍由服务端按 Java 规则（{@code CareerMaterialEvidence}）
 * 与 contentJson 一起计算。
 *
 * <p>已知近似：SQL 侧用 {@code trim(source_text) <> ''} 判定非空，与 Java
 * {@code isBlank()} 相比不把换行/制表符-only 的原文视为空。经 API 创建的资料
 * 本就被证据校验拒绝该形态，故仅影响历史直写行，属既定取舍。
 */
public record CareerMaterialListRow(
        Long id,
        MaterialType materialType,
        String title,
        UsagePreference usagePreference,
        LocalDateTime updatedAt,
        Map<String, Object> contentJson,
        boolean hasSourceText
) {
}