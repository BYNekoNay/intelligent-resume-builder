package com.intelligentresume.careermaterial.dto;

import com.intelligentresume.careermaterial.domain.MaterialType;
import com.intelligentresume.careermaterial.domain.UsagePreference;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.Map;

/**
 * 新建资料的请求体。
 *
 * <p>要点：
 * <ul>
 *   <li>这里的注解只做"字段在不在、长度超不超"的浅校验；类型相关的必填键、
 *       关联资料归属、JSON 字节上限都在 {@code CareerMaterialService} 里做，
 *       改校验必须两边一起看，否则会出现"注解过了但服务层拒"的报错来源混乱；</li>
 *   <li>{@code usagePreference} 可空，服务端缺省填 {@code NORMAL}；不要在 DTO 上写默认值，
 *       迁移接口时容易漏改；</li>
 *   <li>{@code contentJson} 必填（{@code @NotNull}），因为 ACHIEVEMENT 等类型没有它无法校验；</li>
 *   <li>{@code sourceText} 的 65535 是字符数上限，与 contentJson 的<b>字节</b>上限
 *       （{@code app.career-material.content-json.max-bytes}）不是同一套口径。</li>
 * </ul>
 */
public record CreateCareerMaterialRequest(
        @NotNull MaterialType materialType,
        @NotBlank @Size(max = 255) String title,
        @NotNull Map<String, Object> contentJson,
        @Size(max = 65535) String sourceText,
        UsagePreference usagePreference
) {}
