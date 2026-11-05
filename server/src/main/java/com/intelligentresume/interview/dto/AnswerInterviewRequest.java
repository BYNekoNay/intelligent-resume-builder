package com.intelligentresume.interview.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 回答请求。回答长度限制调整至 1-8000。
 *
 * <p>要点：
 * <ul>
 *   <li>8000 与 {@code InterviewContextSanitizer.MAX_CURRENT_ANSWER} 一致，
 *       入库长度与发给模型的长度必须同源，否则会出现「能存但被截断」的错位。</li>
 *   <li>{@code @NotBlank} 会拒绝纯空白回答；真正的 trim 在 Controller 里做。</li>
 *   <li>超长直接由 Bean Validation 拦成 400，不会走到业务层。</li>
 * </ul>
 */
public class AnswerInterviewRequest {
    @NotBlank
    @Size(max = 8000)
    private String answer;

    public String getAnswer() { return answer; }
    public void setAnswer(String answer) { this.answer = answer; }
}
