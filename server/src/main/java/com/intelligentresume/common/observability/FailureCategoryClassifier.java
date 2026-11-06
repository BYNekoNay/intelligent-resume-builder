package com.intelligentresume.common.observability;

import com.intelligentresume.common.error.BusinessException;
import com.intelligentresume.common.error.ErrorCode;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

import java.util.Locale;

/**
 * 失败归类：把异常/错误文本收敛成**低基数枚举**，供指标标签、重试语义与公开文案共用。
 *
 * <p>【小白导览】①解决什么业务问题：上游失败的原因五花八门（超时、断网、额度用完、
 * 返回内容非法……），监控图表和用户文案不可能每种都做——这里把千变万化的失败
 * 压成十来个固定类别（低基数=取值种类少且封闭，适合做统计标签）。
 * ②在链路哪里：AI/PDF worker 判断「要不要重试」、PublicFailureCopy 选公开文案、
 * 指标打失败标签，都找它归类。③改坏它用户会看到什么：可重试的失败被判成永久 →
 * 用户被告知「失败」但其实等一下就能成功；类别文本匹配顺序错了 → 归类张冠李戴。
 *
 * <p>关键点：
 * <ul>
 *   <li>优先看**异常类型**（BusinessException / ResourceAccessException / RestClientResponseException），
 *       只有拿不到类型时才退回文本匹配——文本匹配是会随上游措辞漂移的启发式。</li>
 *   <li>{@code aiMessage}/{@code pdfMessage} 的判断**顺序即优先级**：先匹配 schema/selection/consent，
 *       最后才匹配 5xx/4xx 数字。调换顺序会改变归类结果，进而改变重试策略与用户文案。</li>
 *   <li>归类结果被 {@link com.intelligentresume.common.error.PublicFailureCopy} 映射成对外文案、也可能被 worker 当成「可重试」判据，
 *       新增/合并类别前先确认这两处消费者。</li>
 *   <li>{@code pdfMessage} 同时匹配中文文案：落库的失败原因可能是中文，只匹配英文会漏判。</li>
 * </ul>
 */
@Component
public class FailureCategoryClassifier {

    /** 异常对象 → AI 失败类别；未知一律 INTERNAL（不可重试语义由上层决定）。 */
    public AiFailureCategory ai(Throwable error) {
        if (error instanceof BusinessException business) {
            if (business.getErrorCode() == ErrorCode.CONSENT_REQUIRED) return AiFailureCategory.CONSENT_REVOKED;
            if (business.getErrorCode() == ErrorCode.RATE_LIMITED) return AiFailureCategory.QUOTA_EXHAUSTED;
        }
        if (error instanceof ResourceAccessException) {
            return contains(error.getMessage(), "timeout", "timed out", "read timed")
                    ? AiFailureCategory.TIMEOUT : AiFailureCategory.CONNECTION;
        }
        if (error instanceof RestClientResponseException response) {
            return aiStatus(response.getStatusCode());
        }
        return aiMessage(error == null ? null : error.getMessage());
    }

    /**
     * 纯文本归类（用于已落库的 error_message）。**顺序敏感**：越具体的业务失败越靠前，
     * 数字码（429/5xx/4xx）放在最后，避免正文里恰好出现的 "400" 抢走真正的 schema 失败。
     */
    public AiFailureCategory aiMessage(String message) {
        if (contains(message, "schema", "draft schema")) return AiFailureCategory.SCHEMA_INVALID;
        if (contains(message, "selection", "recommended list", "candidate")) return AiFailureCategory.SELECTION_INVALID;
        if (contains(message, "consent", "authorization was withdrawn")) return AiFailureCategory.CONSENT_REVOKED;
        if (contains(message, "quota", "rate limit", "429")) return AiFailureCategory.QUOTA_EXHAUSTED;
        if (contains(message, "empty choices", "empty message", "empty response", "invalid response")) {
            return AiFailureCategory.PROVIDER_RESPONSE_INVALID;
        }
        if (contains(message, "timeout", "timed out", "read timed")) return AiFailureCategory.TIMEOUT;
        if (contains(message, "connection", "network", "connect")) return AiFailureCategory.CONNECTION;
        if (contains(message, "500", "502", "503", "504")) return AiFailureCategory.PROVIDER_5XX;
        if (contains(message, "400", "401", "403", "404")) return AiFailureCategory.PROVIDER_4XX;
        return AiFailureCategory.INTERNAL;
    }

    public PdfFailureCategory pdf(Throwable error) {
        if (error instanceof ResourceAccessException) {
            return contains(error.getMessage(), "timeout", "timed out", "read timed")
                    ? PdfFailureCategory.TIMEOUT : PdfFailureCategory.CONNECTION;
        }
        if (error instanceof RestClientResponseException response) {
            HttpStatusCode status = response.getStatusCode();
            if (status.value() == 401 || status.value() == 403) return PdfFailureCategory.AUTH;
            if (status.value() == 413) return PdfFailureCategory.INPUT_TOO_LARGE;
            if (status.value() == 503) return PdfFailureCategory.OVERLOADED;
            return PdfFailureCategory.RENDER;
        }
        return pdfMessage(error == null ? null : error.getMessage());
    }

    /** PDF 文本归类。storage 判断放在前面：存储失败的解释比通用渲染失败更具体。 */
    public PdfFailureCategory pdfMessage(String message) {
        if (contains(message, "storage", "store")) return PdfFailureCategory.STORAGE;
        // 体积上限的中文文案（PdfServiceClient 在调用前后各自的判据）：输入＝导出数据，
        // 输出＝导出文件。不依赖英文 "too large" 是因为失败原因以中文原文落库并展示。
        if (contains(message, "导出数据超出最大允许大小")) return PdfFailureCategory.INPUT_TOO_LARGE;
        if (contains(message, "导出文件超出最大允许大小")) return PdfFailureCategory.OUTPUT_TOO_LARGE;
        if (contains(message, "input", "too large", "413")) return PdfFailureCategory.INPUT_TOO_LARGE;
        if (contains(message, "auth", "401", "403")) return PdfFailureCategory.AUTH;
        // 容量/drain 拒绝（pdf-service 503 的可读文案，中文或英文）
        if (contains(message, "容量", "繁忙", "busy", "capacity", "503")) return PdfFailureCategory.OVERLOADED;
        if (contains(message, "timeout", "timed out", "read timed")) return PdfFailureCategory.TIMEOUT;
        if (contains(message, "connection", "connect", "network")) return PdfFailureCategory.CONNECTION;
        return PdfFailureCategory.RENDER;
    }

    private AiFailureCategory aiStatus(HttpStatusCode status) {
        if (status.value() == 429) return AiFailureCategory.RATE_LIMITED;
        return status.is5xxServerError() ? AiFailureCategory.PROVIDER_5XX : AiFailureCategory.PROVIDER_4XX;
    }

    private boolean contains(String value, String... candidates) {
        if (value == null) return false;
        String normalized = value.toLowerCase(Locale.ROOT);
        for (String candidate : candidates) {
            if (normalized.contains(candidate)) return true;
        }
        return false;
    }
}
