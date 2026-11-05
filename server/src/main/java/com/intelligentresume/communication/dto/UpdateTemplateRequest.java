package com.intelligentresume.communication.dto;

import com.intelligentresume.communication.domain.CommunicationOutputLanguage;
import com.intelligentresume.communication.domain.CommunicationType;
import com.intelligentresume.communication.domain.TemplateScene;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 更新自定义模板请求（字段与创建一致）。
 *
 * <p>全量覆盖语义：没传的字段不会"保持原值"，而是按校验规则失败或被补默认值
 * （{@code outputLanguage} 缺省 → {@code ZH_CN}）。因此前端编辑表单必须回传完整字段，
 * 这也是「列表只返回摘要、编辑前必须显式读详情」的原因。
 */
public record UpdateTemplateRequest(
        @NotBlank @Size(max = 128) String name,
        @NotNull TemplateScene scene,
        @NotNull CommunicationType type,
        @NotBlank String bodyText,
        @Size(max = 512) String description,
        CommunicationOutputLanguage outputLanguage
) {}
