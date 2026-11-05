package com.intelligentresume.application.dto;

import com.intelligentresume.application.domain.ApplicationStatus;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 状态迁移请求。
 *
 * <p>要点：
 * <ul>
 *     <li>{@code status} 必须命中 {@code ApplicationService.TRANSITIONS} 白名单，否则 409。</li>
 *     <li>{@code version} 是乐观锁版本号，必填。</li>
 *     <li>{@code feedbackText} 是 PATCH 语义：null＝不改动，空白串＝清空。</li>
 *     <li>{@code note} 只写进状态历史，不回写投递记录。</li>
 * </ul>
 */
public record UpdateApplicationStatusRequest(
        @NotNull ApplicationStatus status,
        @NotNull Long version,
        @Size(max = 30000) String feedbackText,
        @Size(max = 30000) String note
) {
    /** Backward-compatible constructor for clients that only send feedbackText. */
    public UpdateApplicationStatusRequest(ApplicationStatus status, Long version, String feedbackText) {
        this(status, version, feedbackText, null);
    }
}
