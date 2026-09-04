package com.intelligentresume.communication.dto;

import com.intelligentresume.communication.domain.CommunicationOutputLanguage;
import com.intelligentresume.communication.domain.CommunicationType;
import com.intelligentresume.communication.domain.TemplateScene;

/**
 * 模板编辑详情。列表只返回摘要，编辑时必须显式读取正文，避免更新元数据时把正文置空。
 */
public record TemplateDetailResponse(
        Long id,
        TemplateScene scene,
        CommunicationType type,
        CommunicationOutputLanguage outputLanguage,
        String name,
        String description,
        String bodyText,
        boolean isSystem,
        int usageCount
) {}
