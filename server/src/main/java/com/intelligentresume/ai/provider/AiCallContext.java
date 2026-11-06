package com.intelligentresume.ai.provider;

import com.intelligentresume.ai.task.domain.AiTaskType;

import java.util.Map;

/**
 * AI 调用上下文 —— 一次模型调用的输入包：哪种任务 + 用户输入。
 *
 * <p>【链路位置】worker 从任务快照解包出 input 后构造本对象，经注册表传给 provider。
 * 超时由唯一 provider 的全局配置控制（app.ai.bailian.read-timeout-seconds），
 * 不在上下文携带 per-call 超时，避免误导调用方以为存在独立超时窗口。
 * {@code input} 是任务快照里**解包后**的 input(worker 已把它从 {@code inputSnapshotJson}
 * 里取出),内部键(以 {@code _} 开头,如 {@code _systemPrompt})也在这里,由消息组装层消费。</p>
 */
public record AiCallContext(
        AiTaskType type,
        Map<String, Object> input
) {
}
