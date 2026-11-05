package com.intelligentresume.interview.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 薄弱项练习请求。weakness 取自该次报告 weaknesses 中的一条。
 *
 * <p>要点：
 * <ul>
 *   <li>500 字上限与 {@code InterviewFollowUpAiService.validateWeakness} 的手工校验一致；
 *       该字段会原样拼进 prompt，改上限时必须两处同改。</li>
 *   <li>{@code @NotBlank} 拒绝空白串，服务层还会再 trim 并二次判断是否为空。</li>
 *   <li>它同时用于创建任务与发送前预览两个接口，两侧共用同一份校验。</li>
 * </ul>
 */
public record FollowUpPracticeRequest(
        @NotBlank @Size(max = 500) String weakness
) {}
