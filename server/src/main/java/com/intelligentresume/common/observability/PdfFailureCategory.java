package com.intelligentresume.common.observability;

/** Stable, low-cardinality categories for PDF operations. */
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
