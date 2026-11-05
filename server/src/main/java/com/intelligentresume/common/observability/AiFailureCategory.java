package com.intelligentresume.common.observability;

/**
 * AI 失败的稳定低基数类别（同时用作 Prometheus 标签、重试判据与公开文案的分类键）。
 *
 * <p>约定：
 * <ul>
 *   <li>NONE 表示「没有失败」，只在成功样本上出现；失败时不要用它兜底。</li>
 *   <li>新增值必须同步 {@code PublicFailureCopy.aiCopy} 的穷尽 switch（漏了编译失败）与
 *       {@link FailureCategoryClassifier} 的归类逻辑，并检查按标签过滤的告警规则。</li>
 *   <li>不要把 provider 原文/具体错误码当类别——标签基数会爆炸。</li>
 * </ul>
 */
public enum AiFailureCategory {
    NONE,
    TIMEOUT,
    CONNECTION,
    RATE_LIMITED,
    PROVIDER_4XX,
    PROVIDER_5XX,
    PROVIDER_RESPONSE_INVALID,
    SELECTION_INVALID,
    SCHEMA_INVALID,
    CONSENT_REVOKED,
    QUOTA_EXHAUSTED,
    INTERNAL
}
