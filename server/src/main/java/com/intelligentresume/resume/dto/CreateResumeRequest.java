package com.intelligentresume.resume.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.Map;

/**
 * 创建简历请求。
 *
 * <p>{@code resumeJson} 可空：为空表示只建主档（当前版本指针为 null）；
 * 非空时会在同一事务里额外落一条 v1 版本并设为当前版本，且必须先通过 JSON Resume 结构校验。
 */
public record CreateResumeRequest(
        @NotBlank @Size(max = 255) String title,
        Map<String, Object> resumeJson
) {}
