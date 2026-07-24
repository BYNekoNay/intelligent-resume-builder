package com.intelligentresume.ai.confirmation.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 确认请求。用户逐项 ACCEPT / EDIT / REJECT 草稿条目。
 *
 * @param taskUpdatedAt        乐观锁：必须与 ai_task.updated_at 一致，否则 40901
 * @param items                逐条决策列表（不允许一键全选）
 * @param additionalResumeJson 用户主动新增事实（可选，合并到 normalized JSON）
 * @param resumeTitle          岗位简历标题（可选，覆盖默认命名"公司名 - 岗位名"）
 * @param targetResumeId       更新已有简历时传入（可选，不传则自动创建新简历）
 */
public record ConfirmRequest(
        @NotNull LocalDateTime taskUpdatedAt,
        @NotEmpty @Size(max = 200) @Valid List<ConfirmedDraftItem> items,
        Map<String, Object> additionalResumeJson,
        String resumeTitle,
        Long targetResumeId
) {}
