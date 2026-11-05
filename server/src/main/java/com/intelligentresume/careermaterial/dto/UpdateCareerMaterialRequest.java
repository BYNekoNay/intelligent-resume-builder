package com.intelligentresume.careermaterial.dto;

import com.intelligentresume.careermaterial.domain.UsagePreference;
import jakarta.validation.constraints.Size;

import java.util.Map;

/**
 * 更新资料的部分更新（PATCH）请求体：字段为 null 表示"本次不动这个字段"。
 *
 * <p>要点：
 * <ul>
 *   <li><b>null 与空串语义不同</b>：{@code sourceText=""} 会真的把原文清空，
 *       而 {@code title} 为空白串时服务层<b>跳过</b>赋值（保留旧标题），两者不对称，
 *       前端要清空标题目前没有受支持的入口；</li>
 *   <li>{@code materialType} 不在本 DTO 里：类型创建后不可改，改类型等于换一种资料，
 *       必须删旧建新；更新时的类型校验用的是库里已存的类型；</li>
 *   <li>只传部分字段时，证据校验用的是"旧值 + 新值合并后"的结果，
 *       所以单独清空 sourceText 仍可能被 contentJson 兜住而不报错；</li>
 *   <li>本对象没有 {@code @NotNull} 字段，请求体为空对象也是合法的（等于无操作）。</li>
 * </ul>
 */
public record UpdateCareerMaterialRequest(
        @Size(max = 255) String title,
        Map<String, Object> contentJson,
        @Size(max = 65535) String sourceText,
        UsagePreference usagePreference
) {}
