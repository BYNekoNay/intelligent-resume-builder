package com.intelligentresume.export.domain;

/**
 * PDF 导出任务状态。
 *
 * <p>合法流转（无数据库约束，全靠服务层保证）：
 * <ul>
 *     <li>{@code PENDING → RUNNING → SUCCESS / FAILED}：worker 正常领取与结束；</li>
 *     <li>{@code RUNNING → PENDING}：pdf-service 容量拒绝，同时写 {@code next_attempt_at} 延迟重排；</li>
 *     <li>{@code FAILED → PENDING}：用户手动重试（有次数上限）；</li>
 *     <li>{@code SUCCESS → EXPIRED}：文件到期被清理，同时清空 storageKey / 大小 / 校验和。</li>
 * </ul>
 *
 * <p>{@code EXPIRED} 表示"产物过期"，不是"渲染失败"；下载接口对它和 FAILED 都返回不可用。
 */
public enum ExportStatus {
    PENDING,
    RUNNING,
    SUCCESS,
    FAILED,
    EXPIRED
}
