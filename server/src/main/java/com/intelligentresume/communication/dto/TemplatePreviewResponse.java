package com.intelligentresume.communication.dto;

import com.intelligentresume.communication.domain.CommunicationType;
import com.intelligentresume.communication.domain.TemplateScene;

import java.util.List;

/**
 * 模板预览结果。filledBody 已用真实简历/JD 填充白名单占位符；
 * 缺失值占位符原样保留并列入 missingPlaceholders（前端高亮可手补）。
 */
public record TemplatePreviewResponse(
        Long id,
        String name,
        TemplateScene scene,
        CommunicationType type,
        String filledBody,
        List<String> missingPlaceholders
) {}
