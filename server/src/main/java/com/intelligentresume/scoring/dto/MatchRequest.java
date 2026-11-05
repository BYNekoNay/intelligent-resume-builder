package com.intelligentresume.scoring.dto;

import jakarta.validation.constraints.NotNull;

/**
 * 评分请求。
 *
 * <p>只有两个引用 ID，没有权重/阈值参数：分数口径由服务端规则与配置决定，
 * 客户端不能通过请求影响计算方式（否则跨用户不可比）。
 */
public record MatchRequest(
        @NotNull Long resumeVersionId,
        @NotNull Long jobDescriptionId
) {}
