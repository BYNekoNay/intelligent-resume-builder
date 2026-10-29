package com.intelligentresume.export.service;

import com.intelligentresume.common.error.BusinessException;
import com.intelligentresume.common.error.ErrorCode;

/**
 * pdf-service 的**容量拒绝**（503 + {@code Retry-After}）。
 *
 * <p>与其它 PDF 失败的区别是「不是这次渲染有问题，而是现在没有名额」：同样的请求
 * 过一会儿就能成功。所以调用方（{@code ExportTaskWorker}）需要把它从「任务失败」
 * 里分出来 —— 否则一次队列打满就把用户的导出任务永久判死，而 pdf-service 明明
 * 已经通过 {@code Retry-After} 告诉了我们要等多久。
 *
 * <p>此前这个信号在 {@link PdfServiceClient} 里被压平成普通
 * {@code PDF_FAILURE("PDF 服务繁忙，请稍后重试")}：文案是对的，但**执行侧不重试**，
 * 用户看到「请稍后重试」却必须自己回页面点重试 —— 语义断链。
 */
public class PdfCapacityException extends BusinessException {

    /** 兜底等待秒数：pdf-service 未给 {@code Retry-After} 或给的值不可解析时使用。 */
    public static final int DEFAULT_RETRY_AFTER_SECONDS = 2;

    private final int retryAfterSeconds;

    public PdfCapacityException(String message, int retryAfterSeconds) {
        super(ErrorCode.PDF_FAILURE, message);
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public int getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
