package com.intelligentresume.common.persistence;

/**
 * 异步任务失败消息的持久化边界（ideation #514）。
 *
 * <p>AI 任务、PDF 导出任务与面试 AI 尝试的 {@code error_message} 列均为
 * {@code VARCHAR(1024)}，而 provider/异常抛出的 message 长度不可控。超长消息在
 * MySQL 严格模式下会让「释放失败」事务直接失败：任务停留在 RUNNING（或面试尝试
 * 停留在进行中）直到租约过期被接管，真实失败原因反而被掩盖，重试计数与告警口径
 * 也会随之漂移。
 *
 * <p>因此所有写入路径统一经 {@link #persisted(String)} 截断到
 * {@link #MAX_STORED_LENGTH}；PDF 导出任务此前已在租约服务内按 1000 截断，
 * 这里沿用同一约定并收敛到唯一实现。
 */
public final class AsyncFailureMessages {

    /** 小于列宽 1024，留出余量；与 {@code ExportTaskLeaseService} 原有截断长度一致。 */
    static final int MAX_STORED_LENGTH = 1000;

    private AsyncFailureMessages() {
    }

    /**
     * 返回可安全写入 {@code error_message} 的文本：null 原样返回（保持清空语义），
     * 超长按字符截断，且不切断代理对（emoji 等增补字符不会被截成残缺字符）。
     */
    public static String persisted(String message) {
        if (message == null || message.length() <= MAX_STORED_LENGTH) {
            return message;
        }
        int end = MAX_STORED_LENGTH;
        if (Character.isHighSurrogate(message.charAt(end - 1))) {
            end -= 1;
        }
        return message.substring(0, end);
    }
}