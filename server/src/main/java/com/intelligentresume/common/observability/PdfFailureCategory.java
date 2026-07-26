package com.intelligentresume.common.observability;

/** Stable, low-cardinality categories for PDF operations. */
public enum PdfFailureCategory {
    NONE,
    TIMEOUT,
    CONNECTION,
    AUTH,
    INPUT_TOO_LARGE,
    RENDER,
    STORAGE,
    INTERNAL
}
