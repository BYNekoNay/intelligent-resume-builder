package com.intelligentresume.common.observability;

/**
 * PDF 导出失败的稳定低基数类别（指标标签 + 公开文案的分类键）。
 *
 * <p>约定与 {@link AiFailureCategory} 相同：新增值必须同步 {@code PublicFailureCopy.pdfCopy}
 * 的穷尽 switch（漏了编译失败）与 {@link FailureCategoryClassifier#pdfMessage}，并检查按标签过滤的告警规则。
 * 体积超限分成 INPUT/OUTPUT 两类，是因为两者给用户的处置建议不同（减少简历内容 vs 减少导出内容）。
 */
public enum PdfFailureCategory {
    NONE,
    TIMEOUT,
    CONNECTION,
    AUTH,
    INPUT_TOO_LARGE,
    /** 渲染结果超过 `app.pdf.max-output-bytes`（落盘前拒绝，见第五十二批）。 */
    OUTPUT_TOO_LARGE,
    /** PDF 服务容量/drain 拒绝（503，可重试）——ideation「PDF readiness 与容量」。 */
    OVERLOADED,
    RENDER,
    STORAGE,
    INTERNAL
}
