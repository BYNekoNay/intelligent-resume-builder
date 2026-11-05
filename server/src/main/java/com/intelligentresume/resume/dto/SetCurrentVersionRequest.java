package com.intelligentresume.resume.dto;

import jakarta.validation.constraints.NotNull;

/**
 * 切换当前版本请求体（PATCH /api/resumes/{id}/current-version）。
 *
 * <p>要点：
 * <ul>
 *     <li>只改指针，不复制内容——版本本身不会被创建或修改。</li>
 *     <li>{@code versionId} 必须属于该简历，且不能是已归档版本（服务层以 409 拒绝）。</li>
 *     <li>缺失 {@code versionId} 由 Bean Validation 拒绝（400 / VALIDATION），
 *         与改造前手工判空抛 {@code BusinessException(VALIDATION)} 的语义一致。</li>
 * </ul>
 */
public record SetCurrentVersionRequest(
        @NotNull Long versionId
) {
}
