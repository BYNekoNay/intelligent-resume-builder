package com.intelligentresume.interview.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 回答请求。回答长度限制调整至 1-8000。
 */
public class AnswerInterviewRequest {
    @NotBlank
    @Size(max = 8000)
    private String answer;

    public String getAnswer() { return answer; }
    public void setAnswer(String answer) { this.answer = answer; }
}
