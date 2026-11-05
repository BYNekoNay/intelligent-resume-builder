package com.intelligentresume.interview.dto;

import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Map;

/**
 * AI 面试教练 Prompt 上下文，用于构造发给 Provider 的输入。
 *
 * <p>要点：
 * <ul>
 *   <li>当前仓库内没有引用点：实际调用走 {@code AiCallContext} 的 {@code _systemPrompt/_taskPrompt} Map，
 *       本类的两个字段与这两个 key 一一对应。</li>
 *   <li>{@code systemPrompt} 与 {@code userPrompt} 的职责划分与线上一致：前者是角色与硬约束，
 *       后者是这一次的具体任务与数据。</li>
 *   <li>不可变对象且无校验注解：它只承载字符串，契约校验发生在模型响应侧。</li>
 * </ul>
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
