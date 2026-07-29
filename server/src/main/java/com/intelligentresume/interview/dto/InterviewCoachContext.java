package com.intelligentresume.interview.dto;

import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Map;

/**
 * AI 面试教练 Prompt 上下文，用于构造发给 Provider 的输入。
 */
public class InterviewCoachContext {

    private final String systemPrompt;
    private final String userPrompt;

    public InterviewCoachContext(String systemPrompt, String userPrompt) {
        this.systemPrompt = systemPrompt;
        this.userPrompt = userPrompt;
    }

    public String systemPrompt() { return systemPrompt; }
    public String userPrompt() { return userPrompt; }
}
