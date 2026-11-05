package com.intelligentresume.interview.dto;

import com.intelligentresume.interview.domain.InterviewMode;
import com.intelligentresume.interview.domain.InterviewOutputLanguage;
import com.intelligentresume.interview.domain.InterviewSourceType;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 开场请求。
 *
 * <p>要点：
 * <ul>
 *   <li>{@code sourceType} 决定必填项：平台简历要 {@code resumeVersionId}，外部简历要
 *       {@code externalResumeText}；服务层还会再校验一次归属与归档状态。</li>
 *   <li>{@code targetQuestionCount} 在 4-12 之间，服务端还会夹逼一次并据此派生 min/max。</li>
 *   <li>{@code outputLanguage} 为空按 zh-CN 处理。</li>
 *   <li>{@code initialQuestion} 有值时跳过 AI 首题（练习/重放场景），长度上限 500。</li>
 * </ul>
 */
public record StartInterviewRequest(@NotNull InterviewSourceType sourceType, Long resumeVersionId,
                                    @Size(max = 100000) String externalResumeText,
                                    Long jobDescriptionId, @NotNull InterviewMode interviewMode,
                                    @jakarta.validation.constraints.Min(4) @jakarta.validation.constraints.Max(12) Integer targetQuestionCount,
                                    InterviewOutputLanguage outputLanguage,
                                    @Size(max = 500) String initialQuestion) {}
