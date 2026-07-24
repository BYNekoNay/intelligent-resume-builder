package com.intelligentresume.export.domain;

/**
 * PDF 导出任务状态。
 */
public enum ExportStatus {
    PENDING,
    RUNNING,
    SUCCESS,
    FAILED,
    EXPIRED
}
