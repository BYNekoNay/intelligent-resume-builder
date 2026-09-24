package com.intelligentresume.ai.provider;

import com.intelligentresume.common.observability.AiFailureCategory;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 百炼错误响应的分类与「是否顺延到下一个模型」的判定。
 *
 * <p><b>隐私约束</b>：本类只从响应体中提取**机器可读的错误码**（{@code error.code}），
 * 不保存、不返回、不记录响应体其余内容。百炼的 4xx 响应体可能回显请求片段
 * （含简历 / JD 文本），因此禁止把 body 写进日志或异常消息。
 *
 * <p>为什么需要这一层：{@code FailureCategoryClassifier.aiStatus()} 把 403 一律归为
 * {@code PROVIDER_4XX}，无法区分「密钥无效」与「免费额度耗尽」。前者换模型也没用
 * （应立即终止），后者换模型即可恢复（应顺延）。两者混在一起时，模型链无从决策。
 */
final class BailianFailureClassifier {

    /** 单次模型调用失败后，链式调度应采取的动作。 */
    enum Disposition {
        /** 调用成功，无需任何调度动作。 */
        SUCCEEDED,
        /** 顺延到下一个模型，并把当前模型置入长冷却（额度类失效，短期不会恢复）。 */
        NEXT_MODEL_LONG_COOLDOWN,
        /** 顺延到下一个模型，并把当前模型置入短冷却（瞬时故障）。 */
        NEXT_MODEL_SHORT_COOLDOWN,
        /** 立即终止，不顺延：换模型也无济于事，继续尝试只会浪费额度与时间。 */
        ABORT
    }

    private static final Pattern CODE_PATTERN = Pattern.compile("\"code\"\\s*:\\s*\"([^\"]{1,120})\"");
    private static final Pattern TYPE_PATTERN = Pattern.compile("\"type\"\\s*:\\s*\"([^\"]{1,120})\"");

    /** 额度类错误码关键字：免费额度耗尽、欠费、余额不足。 */
    private static final String[] QUOTA_KEYWORDS =
            {"allocationquota", "freetieronly", "quota", "arrearage", "insufficientbalance"};

    /** 限流类错误码关键字。 */
    private static final String[] THROTTLE_KEYWORDS = {"throttling", "ratelimit", "ratelimitexceeded"};

    /** 凭据/授权类错误码关键字：换模型无效，必须立即终止。 */
    private static final String[] CREDENTIAL_KEYWORDS = {"invalidapikey", "authentication", "unauthorized"};

    private BailianFailureClassifier() {
    }

    /**
     * 从响应体中提取机器可读错误码，优先 {@code error.code}，退回 {@code error.type}。
     *
     * @param body 响应体原文；调用方不得将其记录到日志
     * @return 错误码，无法提取时返回 {@code null}
     */
    static String extractErrorCode(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        Matcher codeMatcher = CODE_PATTERN.matcher(body);
        if (codeMatcher.find()) {
            return codeMatcher.group(1);
        }
        Matcher typeMatcher = TYPE_PATTERN.matcher(body);
        return typeMatcher.find() ? typeMatcher.group(1) : null;
    }

    /**
     * 是否为额度耗尽类错误。
     *
     * <p>显式排除限流类：百炼的 {@code Throttling.RateQuota} 也含 "quota" 字样，
     * 但它是**瞬时**限流而非额度耗尽。若在此处误判为真，调用方无论按什么顺序判断，
     * 都会把限流当成额度耗尽并触发长冷却。把排除逻辑放在谓词内部，比依赖调用顺序更可靠。
     */
    static boolean isQuotaExhausted(String errorCode) {
        if (isThrottling(errorCode)) {
            return false;
        }
        return matches(errorCode, QUOTA_KEYWORDS);
    }

    static boolean isThrottling(String errorCode) {
        return matches(errorCode, THROTTLE_KEYWORDS);
    }

    static boolean isCredentialFailure(String errorCode) {
        return matches(errorCode, CREDENTIAL_KEYWORDS);
    }

    /**
     * 依据 HTTP 状态与错误码判定失败分类。
     *
     * <p>判定顺序很重要：限流类错误码本身可能含 "quota"（如 {@code Throttling.RateQuota}），
     * 若先匹配额度关键字，会把**瞬时限流误判为额度耗尽**，进而让模型被长冷却 30 分钟。
     * 因此先匹配更具体的限流关键字，再匹配额度关键字。
     */
    static AiFailureCategory categoryFor(int status, String errorCode) {
        if (isThrottling(errorCode)) {
            return AiFailureCategory.RATE_LIMITED;
        }
        if (isQuotaExhausted(errorCode)) {
            return AiFailureCategory.QUOTA_EXHAUSTED;
        }
        if (status == 429) {
            return AiFailureCategory.RATE_LIMITED;
        }
        if (status >= 500) {
            return AiFailureCategory.PROVIDER_5XX;
        }
        return AiFailureCategory.PROVIDER_4XX;
    }

    /**
     * 依据 HTTP 状态与错误码判定链式调度动作。
     *
     * <p><b>400 类为何顺延而非终止</b>（2026-09-24 依实测修正）：
     * 曾把 400 归为 {@link Disposition#ABORT}，理由是"可能是我们构造的请求体非法，
     * 顺延会把同一个坏请求在 8 个模型上各发一遍"。实测证明这个取舍是错的 ——
     * 400 往往**是模型特有的**：
     * <ul>
     *   <li>{@code glm-5.3} 与 {@code qwen3.8-2.4t-a95b} 拒绝 {@code enable_thinking} 参数；</li>
     *   <li>{@code kimi-k3} 干脆拒绝 {@code temperature} 参数（本应用的每次请求都带它）。</li>
     * </ul>
     * 若对 400 直接终止，链上任意一个模型的能力不匹配都会让**整条链死掉**，
     * 而正确行为是跳过它、用下一个模型。代价也支持这个选择：400 在 1s 内返回，
     * 即便真是我们的请求体有问题，走完 8 个模型也只需约 10s，远低于超时类失败。
     *
     * <p>仍保留 {@link Disposition#ABORT} 的只有**凭据类**失败：那是账号级问题，
     * 对每个模型都会同样失败，顺延纯属浪费。
     */
    static Disposition dispositionFor(int status, String errorCode) {
        if (isCredentialFailure(errorCode)) {
            return Disposition.ABORT;
        }
        // 同 categoryFor：限流关键字必须先于额度关键字匹配，否则 RateQuota 会被当成额度耗尽而长冷却
        if (isThrottling(errorCode)) {
            return Disposition.NEXT_MODEL_SHORT_COOLDOWN;
        }
        if (isQuotaExhausted(errorCode)) {
            return Disposition.NEXT_MODEL_LONG_COOLDOWN;
        }
        if (status == 429) {
            return Disposition.NEXT_MODEL_SHORT_COOLDOWN;
        }
        if (status == 404) {
            return Disposition.NEXT_MODEL_LONG_COOLDOWN;
        }
        if (status >= 500) {
            return Disposition.NEXT_MODEL_SHORT_COOLDOWN;
        }
        if (status >= 400) {
            // 含 400 InvalidParameter：快速失败，跳过该模型继续尝试
            return Disposition.NEXT_MODEL_SHORT_COOLDOWN;
        }
        return Disposition.NEXT_MODEL_SHORT_COOLDOWN;
    }

    /** 非 HTTP 异常（传输层、响应结构异常）按已判定的分类映射动作。 */
    static Disposition dispositionForCategory(AiFailureCategory category) {
        return switch (category) {
            case NONE -> Disposition.SUCCEEDED;
            case QUOTA_EXHAUSTED -> Disposition.NEXT_MODEL_LONG_COOLDOWN;
            case TIMEOUT, CONNECTION, RATE_LIMITED, PROVIDER_5XX, PROVIDER_RESPONSE_INVALID ->
                    Disposition.NEXT_MODEL_SHORT_COOLDOWN;
            default -> Disposition.ABORT;
        };
    }

    static boolean isChainable(AiFailureCategory category) {
        return dispositionForCategory(category) != Disposition.ABORT;
    }

    private static boolean matches(String errorCode, String[] keywords) {
        if (errorCode == null || errorCode.isBlank()) {
            return false;
        }
        String normalized = errorCode.toLowerCase(Locale.ROOT);
        for (String keyword : keywords) {
            if (normalized.contains(keyword)) {
                return true;
            }
        }
        return false;
    }
}
