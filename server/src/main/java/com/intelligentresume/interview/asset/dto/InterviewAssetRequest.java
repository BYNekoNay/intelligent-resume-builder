package com.intelligentresume.interview.asset.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Map;

/**
 * 创建/更新答案资产的请求体（创建与更新共用同一结构）。
 *
 * <p>要点：
 * <ul>
 *   <li>{@code questionText} / {@code originalAnswerText} 必填，上限分别为 10000 / 20000。</li>
 *   <li>{@code feedbackJson} 原样落库，服务端不校验其内部结构 —— 报告侧读取时对缺失 key 做了兜底。</li>
 *   <li>{@code sectionKeys} 必须是章节白名单；{@code materialIds} 必须属于当前用户。</li>
 *   <li>更新时这两个列表按全量替换处理：传 null 等于清空关联，而不是保持不变。</li>
 * </ul>
 */
public record InterviewAssetRequest(Long interviewRecordId,
                                    @NotBlank @Size(max = 10000) String questionText,
                                    @NotBlank @Size(max = 20000) String originalAnswerText,
                                    @Size(max = 20000) String suggestedAnswerText,
                                    Map<String, Object> feedbackJson,
                                    List<String> sectionKeys,
                                    List<Long> materialIds) {}
