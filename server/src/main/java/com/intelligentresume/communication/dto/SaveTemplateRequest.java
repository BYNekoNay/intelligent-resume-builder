package com.intelligentresume.communication.dto;

import com.intelligentresume.communication.domain.CommunicationOutputLanguage;
import com.intelligentresume.communication.domain.CommunicationType;
import com.intelligentresume.communication.domain.TemplateScene;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 保存自定义模板请求。bodyText 占位符必须来自白名单。
 */
public record SaveTemplateRequest(
        @NotBlank @Size(max = 128) String name,
        @NotNull TemplateScene scene,
        @NotNull CommunicationType type,
        @NotBlank String bodyText,
        @Size(max = 512) String description,
        CommunicationOutputLanguage outputLanguage
) {}
