package com.intelligentresume.resume.dto;

import com.intelligentresume.resume.domain.ResumeSourceType;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.Map;

/**
 * 保存版本请求。
 *
 * <p>{@code sourceType} 由客户端声明（落库审计用），服务端不校验它与实际内容是否相符；
 * {@code resumeJson} 必须通过 {@code JsonResumeValidator} 的结构与体积校验才会入库。
 */
public record SaveVersionRequest(
        @NotNull Map<String, Object> resumeJson,
        @NotNull ResumeSourceType sourceType,
        @Size(max = 512) String optimizationSummary
) {}
