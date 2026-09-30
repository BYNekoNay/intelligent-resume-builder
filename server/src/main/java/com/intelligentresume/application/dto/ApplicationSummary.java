package com.intelligentresume.application.dto;

import com.intelligentresume.application.domain.ApplicationStatus;

import java.time.LocalDateTime;

/**
 * 投递记录列表摘要（ideation #50）。
 *
 * <p>不返回草稿长文本（{@code coverLetterText} / {@code emailBodyText} /
 * {@code openingMessageText}）——它们只在展开卡片或打开编辑面板时按需从详情接口
 * （{@code GET /api/applications/{id}}）拉取；卡片上的「n/3」标记由 {@code draftCount} 支撑。
 *
 * <p>{@code feedbackText} 仍保留在摘要里：列表页的客户端搜索会匹配备注文本，
 * 展开面板的备注编辑框也以它为初值（详情接口同时返回，用于按需刷新）。
 * 状态迁移接口已改为「未发送即保留」，客户端无需再为防清空而回传该值。
 */
public record ApplicationSummary(
        Long id, Long jobDescriptionId, Long resumeVersionId, ApplicationStatus status,
        String feedbackText, int draftCount,
        LocalDateTime appliedAt, LocalDateTime nextFollowUpAt, Long version,
        LocalDateTime createdAt, LocalDateTime updatedAt
) {}