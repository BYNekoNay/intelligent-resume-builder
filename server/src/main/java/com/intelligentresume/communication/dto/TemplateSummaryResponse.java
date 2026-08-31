package com.intelligentresume.communication.dto;

import com.intelligentresume.communication.domain.CommunicationOutputLanguage;
import com.intelligentresume.communication.domain.CommunicationType;
import com.intelligentresume.communication.domain.TemplateScene;

/**
 * 模板摘要（列表项）。内置模板在前，按 usageCount 降序。
 */
public record TemplateSummaryResponse(
        Long id,
        TemplateScene scene,
        CommunicationType type,
        CommunicationOutputLanguage outputLanguage,
        String name,
        String description,
        boolean isSystem,
        int usageCount
) {}
