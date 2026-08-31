package com.intelligentresume.interview.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 薄弱项练习请求。weakness 取自该次报告 weaknesses 中的一条。
 */
public record FollowUpPracticeRequest(
        @NotBlank @Size(max = 500) String weakness
) {}
