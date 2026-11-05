package com.intelligentresume.application.dto;

import com.intelligentresume.application.domain.ApplicationStatus;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 投递记录详情响应（含长文本与状态历史）。
 *
 * <p>要点：
 * <ul>
 *     <li>{@code statusHistory} 按时间升序，是审计时间线的唯一来源；列表摘要不含它。</li>
 *     <li>{@code version} 是乐观锁版本号，客户端做更新时必须原样回传。</li>
 *     <li>下面那个少一个参数的构造器只为兼容旧测试代码，运行时一律走带 history 的版本。</li>
 * </ul>
 */
public record ApplicationResponse(
        Long id, Long jobDescriptionId, Long resumeVersionId, ApplicationStatus status,
        String coverLetterText, String emailBodyText, String openingMessageText, String feedbackText,
        LocalDateTime appliedAt, LocalDateTime nextFollowUpAt, Long version, LocalDateTime createdAt, LocalDateTime updatedAt,
        List<ApplicationStatusHistoryResponse> statusHistory
) {
    /** Source compatibility for callers that construct the pre-history response in tests. */
    public ApplicationResponse(Long id, Long jobDescriptionId, Long resumeVersionId, ApplicationStatus status,
                               String coverLetterText, String emailBodyText, String openingMessageText, String feedbackText,
                               LocalDateTime appliedAt, LocalDateTime nextFollowUpAt, Long version,
                               LocalDateTime createdAt, LocalDateTime updatedAt) {
        this(id, jobDescriptionId, resumeVersionId, status, coverLetterText, emailBodyText, openingMessageText,
                feedbackText, appliedAt, nextFollowUpAt, version, createdAt, updatedAt, List.of());
    }
}
