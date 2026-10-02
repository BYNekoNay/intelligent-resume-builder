package com.intelligentresume.common.error;

import com.intelligentresume.common.observability.AiFailureCategory;
import com.intelligentresume.common.observability.FailureCategoryClassifier;
import com.intelligentresume.common.observability.PdfFailureCategory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 公开文案边界的核心断言：**provider / 框架原文不得出现在面向客户端的文案里**。
 *
 * <p>样本刻意取**真实会落库的形态**（英文、含模型名、HTTP 状态、上游错误体、内部字节数）——
 * 这正是原先会被原样回给前端的文本。断言两个方向：
 * ① 文案必须等于该类别约定的固定文案；② 文案里不得残留样本中的任何敏感片段。
 */
class PublicFailureCopyTest {

    private final PublicFailureCopy copy = new PublicFailureCopy(new FailureCategoryClassifier());

    /** 真实 provider / 异常原文样本（`ai_task.error_message` 会落成这些样子）。 */
    private static final List<String> AI_RAW_SAMPLES = List.of(
            "Resume generation failed: HTTP 429 quota exceeded for model qwen3.8-max",
            "Draft schema validation failed: $.basics.name required",
            "java.net.SocketTimeoutException: Read timed out after 300000ms calling "
                    + "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions",
            "org.springframework.web.client.ResourceAccessException: Connection refused: connect to pdf-service:3001",
            "AI authorization was withdrawn by user",
            "Failed to parse: empty choices in response from deepseek-v4-pro-0813");

    /** 面向客户端的文案里**不允许**出现的片段。 */
    private static final List<String> FORBIDDEN_FRAGMENTS = List.of(
            "qwen", "deepseek", "dashscope", "http", "socket", "exception", "bytes",
            "429", "300000", "3001", "schema", "failed", "withdrawn", "choices");

    @Test
    @DisplayName("AI：真实 provider 原文一律换成本类别约定的稳定文案")
    void aiRawProviderTextIsReplacedByStableCopy() {
        assertEquals(PublicFailureCopy.AI_SERVICE_UNAVAILABLE,
                copy.forAiTask("Resume generation failed: HTTP 429 quota exceeded for model qwen3.8-max"),
                "额度/限流类应归入「服务暂时不可用」");
        assertEquals(PublicFailureCopy.AI_RESULT_UNUSABLE,
                copy.forAiTask("Draft schema validation failed: $.basics.name required"),
                "schema 校验失败属「结果不可用」，提示用户调整输入");
        assertEquals(PublicFailureCopy.AI_SERVICE_UNAVAILABLE,
                copy.forAiTask("java.net.SocketTimeoutException: Read timed out after 300000ms"),
                "读超时归入「服务暂时不可用」");
        assertEquals(PublicFailureCopy.AI_REAUTHORIZATION_REQUIRED,
                copy.forAiTask("AI authorization was withdrawn by user"),
                "同意撤回要让用户知道去重新授权（与面试路径的 reauthorizationRequired 同口径）");
        assertEquals(PublicFailureCopy.AI_RESULT_UNUSABLE,
                copy.forAiTask("Failed to parse: empty choices in response from deepseek-v4-pro-0813"),
                "上游响应非法属「结果不可用」");
        assertNull(copy.forAiTask(null),
                "null 必须原样返回 null —— 重试会清空 error_message，「没有错误」是有意义的状态，"
                        + "映射层凭空造文案会让 UI 误报失败");
        assertNull(copy.forAiTask("   "), "空白串同 null");
    }

    @Test
    @DisplayName("出错信封必有文案：即使 message 为空也要给通用文案（与状态响应的 null 语义不同）")
    void envelopeAlwaysCarriesCopyEvenWhenMessageBlank() {
        assertEquals(PublicFailureCopy.AI_RESULT_UNUSABLE,
                copy.forBusinessEnvelope(ErrorCode.AI_FAILURE, null));
        assertEquals(PublicFailureCopy.PDF_SERVICE_UNAVAILABLE,
                copy.forBusinessEnvelope(ErrorCode.PDF_FAILURE, null));
        assertNull(copy.forBusinessEnvelope(ErrorCode.CONFLICT, null),
                "其余码保持原样（null 即 null），不做无依据的改写");
    }

    @Test
    @DisplayName("AI：样本里的敏感片段（模型名/HTTP 状态/上游错误体）一个都不许残留")
    void aiCopyNeverContainsRawFragments() {
        assertTrue(AI_RAW_SAMPLES.size() >= 5, "样本过少，本用例会失去意义（当前 " + AI_RAW_SAMPLES.size() + "）");
        for (String raw : AI_RAW_SAMPLES) {
            String copyText = copy.forAiTask(raw);
            assertFalse(copyText.isBlank(), "不得给出空文案：" + raw);
            for (String fragment : FORBIDDEN_FRAGMENTS) {
                assertFalse(copyText.toLowerCase(java.util.Locale.ROOT).contains(fragment),
                        "公开文案里出现了原始片段「" + fragment + "」：" + copyText + "（原文：" + raw + "）");
            }
            assertTrue(copyText.length() <= 60,
                    "公开文案应短且稳定，不应夹带细节：" + copyText);
        }
    }

    @Test
    @DisplayName("PDF：体积超限保留可行动性，但不带内部字节数")
    void pdfSizeLimitsStayActionableWithoutInternalNumbers() {
        assertEquals(PublicFailureCopy.PDF_INPUT_TOO_LARGE,
                copy.forExportTask("导出数据超出最大允许大小 (67108864 bytes)"),
                "输入超限用户可自行减少内容，必须保留这条可行动文案");
        assertEquals(PublicFailureCopy.PDF_OUTPUT_TOO_LARGE,
                copy.forExportTask("导出文件超出最大允许大小 (9000000 > 8388608 bytes)"),
                "输出超限同样可行动");
        assertFalse(copy.forExportTask("导出数据超出最大允许大小 (67108864 bytes)").contains("67108864"),
                "内部字节上限不得外泄");
        assertEquals(PublicFailureCopy.PDF_SERVICE_UNAVAILABLE,
                copy.forExportTask("PDF 服务繁忙，请稍后重试（pdf-service 503 drain）"),
                "容量/drain 拒绝归入「服务暂时不可用」");
        assertEquals(PublicFailureCopy.PDF_SERVICE_UNAVAILABLE,
                copy.forExportTask("java.net.SocketTimeoutException: Read timed out"));
    }

    @Test
    @DisplayName("信封：只有 AI/PDF 两个码改写；其余业务码的中文用户文案原样保留")
    void envelopeOnlyRewritesProviderDetailCodes() {
        String rawProvider = "Resume generation failed: HTTP 502 Bad Gateway from qwen3.8-max";
        assertEquals(PublicFailureCopy.AI_SERVICE_UNAVAILABLE,
                copy.forBusinessEnvelope(ErrorCode.AI_FAILURE, rawProvider));
        assertEquals(PublicFailureCopy.PDF_SERVICE_UNAVAILABLE,
                copy.forBusinessEnvelope(ErrorCode.PDF_FAILURE, "PDF render failed: connection reset"));

        String userFacing = "当前不在等待回答状态";
        assertEquals(userFacing, copy.forBusinessEnvelope(ErrorCode.CONFLICT, userFacing),
                "非 AI/PDF 码的 message 是项目自己的用户文案，必须原样保留（否则会丢掉具体原因）");
        assertEquals(userFacing, copy.forBusinessEnvelope(ErrorCode.VALIDATION, userFacing));

        assertTrue(copy.shouldLogRawMessage(ErrorCode.AI_FAILURE));
        assertTrue(copy.shouldLogRawMessage(ErrorCode.PDF_FAILURE));
        assertFalse(copy.shouldLogRawMessage(ErrorCode.CONFLICT),
                "普通业务冲突不该刷原文日志（可能是用户数据）");
    }

    @Test
    @DisplayName("穷尽性：每个失败类别都必须有非空公开文案（新增类别漏配会先编译失败）")
    void everyCategoryHasCopy() {
        for (AiFailureCategory category : AiFailureCategory.values()) {
            assertFalse(PublicFailureCopy.aiCopy(category).isBlank(), "AI 类别缺文案：" + category);
        }
        for (PdfFailureCategory category : PdfFailureCategory.values()) {
            assertFalse(PublicFailureCopy.pdfCopy(category).isBlank(), "PDF 类别缺文案：" + category);
        }
    }
}
