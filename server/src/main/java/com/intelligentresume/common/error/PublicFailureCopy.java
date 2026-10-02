package com.intelligentresume.common.error;

import com.intelligentresume.common.observability.AiFailureCategory;
import com.intelligentresume.common.observability.FailureCategoryClassifier;
import com.intelligentresume.common.observability.PdfFailureCategory;
import org.springframework.stereotype.Component;

/**
 * 失败消息的**公开文案边界**：面向客户端的文案必须**稳定**，绝不能是 provider / 框架的原文。
 *
 * <p>为什么需要它：`ai_task.error_message`、`export_task.error_message` 里落的是
 * provider 与异常的**原始 message**（如 `Resume generation failed: ...`、
 * `Draft schema validation failed: ...`、HTTP 响应体片段、模型名）。这些字段此前被
 * **原样**放进 {@code AiTaskStatusResponse.errorMessage} / {@code ExportTaskStatusResponse.errorMessage}
 * 与出错信封，而前端会直接渲染（`web/src/api/ai.ts`、`ExportView.vue` …）。后果有三：
 * <ol>
 *   <li><b>信息外泄</b>：把内部实现细节（模型名、限额、上游错误体）暴露给终端用户；</li>
 *   <li><b>文案不稳定</b>：同一失败的可见文案随上游措辞变化，无法用于测试与客服话术；</li>
 *   <li><b>不可本地化</b>：English 原文出现在中文界面里。</li>
 * </ol>
 *
 * <p>做法：**复用已有的失败分类**（{@link FailureCategoryClassifier} → 低基数枚举），
 * 在**响应装配点**把原文换成该类别的固定文案；**原文仍然完整保留在数据库列与日志里**
 * （排查与告警口径不受影响）。这与面试路径既有的做法一致 ——
 * {@code InterviewStateResponse.AiFailureInfo} 只暴露 {@code messageCode}，从不暴露原文。
 *
 * <p><b>刻意不做</b>（登记为后续项）：把类别本身也放进响应、让前端按类别本地化。
 * 那是一次 DTO 契约变更（含前端类型与 i18n 文案），属产品文案层；当前终端的展示需求只是
 * 「给一句稳定、可重试的话」，前端已有 `|| t(...)` 兜底。
 *
 * <p>类别到文案的映射用**穷尽 switch**（不写 default）：新增类别时忘记给文案会**编译失败**，
 * 而不是静默退回上一行文案。
 */
@Component
public class PublicFailureCopy {

    /** 需重新授权（同意被撤回）：用户可自行解决，故单独给一条。 */
    public static final String AI_REAUTHORIZATION_REQUIRED = "AI 授权已失效，请重新授权后重试";

    /** 服务侧暂时不可用（超时/连接/限流/额度/上游 4xx·5xx）：可重试。 */
    public static final String AI_SERVICE_UNAVAILABLE = "AI 服务暂时不可用，请稍后重试";

    /** 上游返回了内容但不可用（响应非法/选材非法/schema 校验失败/其它内部错误）：需要换输入或重试。 */
    public static final String AI_RESULT_UNUSABLE = "AI 生成结果异常，请重试或调整输入后再试";

    /** 输入体积超限：用户可自行解决（减少内容），故保留可行动性；**不带内部字节数**。 */
    public static final String PDF_INPUT_TOO_LARGE = "导出数据超出最大允许大小，请减少内容后重试";

    /** 输出体积超限：同上。 */
    public static final String PDF_OUTPUT_TOO_LARGE = "导出内容过多导致文件超出上限，请减少内容后重试";

    /** PDF 服务暂时不可用（渲染/通道/容量/存储）：可重试。 */
    public static final String PDF_SERVICE_UNAVAILABLE = "PDF 导出服务暂时不可用，请稍后重试";

    private final FailureCategoryClassifier classifier;

    public PublicFailureCopy(FailureCategoryClassifier classifier) {
        this.classifier = classifier;
    }

    /**
     * AI 任务状态响应用的公开文案（入参是落库的原始 message）。
     *
     * <p><b>null / 空串必须原样返回 null</b>：重试会把 {@code error_message} 清空，
     * 「没有错误」这件事本身是有意义的状态 —— 映射层凭空造一句文案会让「无错误」变成「有错误」
     * （第六十九批实测：{@code AiTaskServiceTest.retry_authorized_requeuesWithoutIncrementingAttempt}
     * 正是这样抓到了过度映射）。
     */
    public String forAiTask(String rawMessage) {
        return isBlank(rawMessage) ? null : aiCopy(classifier.aiMessage(rawMessage));
    }

    /** 导出任务状态响应用的公开文案（入参是落库的原始 message）。null 语义同 {@link #forAiTask}。 */
    public String forExportTask(String rawMessage) {
        return isBlank(rawMessage) ? null : pdfCopy(classifier.pdfMessage(rawMessage));
    }

    private static boolean isBlank(String raw) {
        return raw == null || raw.isBlank();
    }

    /**
     * 出错信封用的公开文案。
     *
     * <p>**只改会夹带上游细节的两个码**（AI/PDF 失败）；其余业务码的 message 本就是项目自己写的
     * 中文用户文案（如「当前不在等待回答状态」），原样保留。
     *
     * <p>与 {@link #forAiTask} 不同：信封既然已经出错，**必须有文案**，故 null 也给出通用文案
     * （而不是回 null 让前端空着）。
     */
    public String forBusinessEnvelope(ErrorCode code, String rawMessage) {
        return switch (code) {
            case AI_FAILURE -> aiCopy(classifier.aiMessage(rawMessage));
            case PDF_FAILURE -> pdfCopy(classifier.pdfMessage(rawMessage));
            default -> rawMessage;
        };
    }

    /** 是否需要把原始 message 记进日志（只有会被改写的两个码需要，避免业务文案刷日志）。 */
    public boolean shouldLogRawMessage(ErrorCode code) {
        return code == ErrorCode.AI_FAILURE || code == ErrorCode.PDF_FAILURE;
    }

    /** 类别 → AI 公开文案（穷尽 switch：新增类别必须在此登记，否则编译失败）。 */
    static String aiCopy(AiFailureCategory category) {
        return switch (category) {
            case CONSENT_REVOKED -> AI_REAUTHORIZATION_REQUIRED;
            case TIMEOUT, CONNECTION, RATE_LIMITED, QUOTA_EXHAUSTED, PROVIDER_4XX, PROVIDER_5XX ->
                    AI_SERVICE_UNAVAILABLE;
            case PROVIDER_RESPONSE_INVALID, SELECTION_INVALID, SCHEMA_INVALID, INTERNAL, NONE ->
                    AI_RESULT_UNUSABLE;
        };
    }

    /** 类别 → PDF 公开文案（穷尽 switch：同上）。 */
    static String pdfCopy(PdfFailureCategory category) {
        return switch (category) {
            case INPUT_TOO_LARGE -> PDF_INPUT_TOO_LARGE;
            case OUTPUT_TOO_LARGE -> PDF_OUTPUT_TOO_LARGE;
            case TIMEOUT, CONNECTION, AUTH, OVERLOADED, RENDER, STORAGE, INTERNAL, NONE ->
                    PDF_SERVICE_UNAVAILABLE;
        };
    }
}
