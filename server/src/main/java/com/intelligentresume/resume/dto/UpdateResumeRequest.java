package com.intelligentresume.resume.dto;

import jakarta.validation.constraints.Size;

/**
 * 更新简历主档请求。目前只有标题可改；{@code title} 为空或空白时服务层会忽略，不当作"清空标题"。
 */
public record UpdateResumeRequest(
        @Size(max = 255) String title
) {}
