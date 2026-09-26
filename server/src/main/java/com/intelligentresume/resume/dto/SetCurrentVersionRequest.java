package com.intelligentresume.resume.dto;

import jakarta.validation.constraints.NotNull;

/**
 * 切换当前版本请求体（PATCH /api/resumes/{id}/current-version）。
 *
 * <p>缺失 {@code versionId} 由 Bean Validation 拒绝（400 / VALIDATION），
 * 与改造前手工判空抛 {@code BusinessException(VALIDATION)} 的语义一致。
 */
public record SetCurrentVersionRequest(
        @NotNull Long versionId
) {
}
