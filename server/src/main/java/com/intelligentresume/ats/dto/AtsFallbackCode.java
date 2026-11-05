package com.intelligentresume.ats.dto;

/**
 * AI 分析没能完成时的原因码（落进 {@code result_json.fallback.code}）。
 *
 * <ul>
 *   <li>{@link #AI_DISABLED} —— 环境没有可用的 AI provider / 密钥，不可重试；
 *       {@link #PREVIEW_REQUIRED} —— 没带预览令牌，首次检查时出现属正常，补预览后可重试。</li>
 *   <li>{@link #CONSENT_REQUIRED} —— 用户还没授权 AI 处理简历与岗位；
 *       {@link #QUOTA_EXCEEDED} —— 当日配额用尽（当天重试也没用）。</li>
 *   <li>{@link #PROVIDER_TIMEOUT} / {@link #PROVIDER_ERROR} —— 模型侧超时/报错；超时是字符串匹配判定，
 *       文案一变就会落到后者。</li>
 *   <li>{@link #INVALID_RESPONSE} —— 模型输出不符合 schema；服务端已自动修复重试过一次。</li>
 *   <li>{@link #UNKNOWN} —— 启动阶段的其它异常兜底。</li>
 * </ul>
 *
 * <p>前端 {@code AtsCheckView} 里有一张"码 → i18n 文案"的映射表；新增枚举值时要同步补上，
 * 否则该码在界面上没有对应文案。
 */
public enum AtsFallbackCode {
    AI_DISABLED,
    PREVIEW_REQUIRED,
    CONSENT_REQUIRED,
    QUOTA_EXCEEDED,
    PROVIDER_TIMEOUT,
    PROVIDER_ERROR,
    INVALID_RESPONSE,
    UNKNOWN
}
