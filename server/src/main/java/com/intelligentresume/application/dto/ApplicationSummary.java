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
 * <p>{@code feedbackText} 仍保留在摘要里：状态迁移接口按请求值覆盖备注，
 * 列表必须携带当前值，否则「拖拽改状态」会把已有备注清空。
 */
public record ApplicationSummary(
        Long id, Long jobDescriptionId, Long resumeVersionId, ApplicationStatus status,
        String feedbackText, int draftCount,
        LocalDateTime appliedAt, LocalDateTime nextFollowUpAt, Long version,
        LocalDateTime createdAt, LocalDateTime updatedAt
) {}