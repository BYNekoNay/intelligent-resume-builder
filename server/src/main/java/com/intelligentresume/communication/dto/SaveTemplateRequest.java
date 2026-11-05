package com.intelligentresume.communication.dto;

import com.intelligentresume.communication.domain.CommunicationOutputLanguage;
import com.intelligentresume.communication.domain.CommunicationType;
import com.intelligentresume.communication.domain.TemplateScene;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 保存自定义模板请求。bodyText 占位符必须来自白名单。
 *
 * <p>要点：
 * <ul>
 *   <li>{@code outputLanguage} 可空，服务端落库时补 {@code ZH_CN}（见 {@code CommunicationTemplateService#saveCustom}）。</li>
 *   <li>{@code name}/{@code description} 的长度限制在这里，占位符合法性在 service 层校验，两处都不要省略。</li>
 * </ul>
 */
public record SaveTemplateRequest(
        @NotBlank @Size(max = 128) String name,
        @NotNull TemplateScene scene,
        @NotNull CommunicationType type,
        @NotBlank String bodyText,
        @Size(max = 512) String description,
        CommunicationOutputLanguage outputLanguage
) {}
