package com.intelligentresume.communication.service;

/**
 * 沟通 AI 链路的业务异常，额外携带「是否值得重试」。
 *
 * <p>约定：
 * <ul>
 *   <li>{@code retryable=true} 只来自 provider 返回的可重试错误（网络/限流等），由任务框架决定是否重排。</li>
 *   <li>校验失败、输入解析失败、类型非法一律 {@code false}：重试不会让坏数据变好。</li>
 *   <li>本异常不映射 HTTP 状态码，它只跑在 worker 线程里。</li>
 * </ul>
 */
public class CommunicationAiException extends RuntimeException {
    private final boolean retryable;

    public CommunicationAiException(String message, boolean retryable) {
        super(message);
        this.retryable = retryable;
    }

    public boolean retryable() {
        return retryable;
    }
}
