package com.intelligentresume.interview.asset.dto;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 答案资产响应。{@code sectionKeys} / {@code materialIds} 已去重保序，
 * 是关联表按资产聚合后的结果，不保证与写入时传入的顺序或重复次数一致。
 *
 * <p>要点：
 * <ul>
 *   <li>正文三个字段与实体同名字段一一对应，{@code suggestedAnswerText} 可能为 null。</li>
 *   <li>{@code feedbackJson} 原样返回落库结构，前端自行决定取哪些 key。</li>
 *   <li>时间戳来自实体审计字段，软删除后该资产不会再出现在列表里。</li>
 * </ul>
 */
public record InterviewAssetResponse(Long id, Long interviewRecordId, String questionText,
                                     String originalAnswerText, String suggestedAnswerText,
                                     Map<String, Object> feedbackJson, LocalDateTime createdAt,
                                     LocalDateTime updatedAt,
                                     List<String> sectionKeys, List<Long> materialIds) {}
