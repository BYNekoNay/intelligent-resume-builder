package com.intelligentresume.contract;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 重试端点「原样复用已确认输入」契约（D8，ADR 见 DECISION-BRIEF D8）。
 *
 * <p>背景：R32 给 5 个领域的 AI **创建**端点加了发送前预览令牌（漏令牌即 40001）。
 * 重试类端点（`/optimize/tasks/{id}/retry`、`/interviews/{id}/ai/retry`、
 * 导出重试）**刻意不纳入**令牌：它们不重新组装 prompt，只是把同一条失败记录
 * （同一份 `input_snapshot_json` / 同一次 attempt 的同一问同一答）重新排队，
 * 用户看到的内容与上次逐字相同 —— 再加一次预览点击只增加摩擦，不增加知情。
 *
 * <p>这个「不重新组装」的性质是**决策的前提**，不是实现细节：一旦某个 retry 开始
 * 重新组装输入（换模型改 messages、重新选材、按新草稿重算），它就必须纳入令牌，
 * 且 `docs/05` 要写「重试前的预览可能与此前不同」。本门禁把该前提钉死：
 * 谁把组装逻辑塞进 retry，谁就会在这里变红，然后必须去改决策与文档。
 */
class RetryReuseContractTest {

    private static final String TASK_SERVICE =
            "server/src/main/java/com/intelligentresume/ai/task/service/AiTaskService.java";
    private static final String INTERVIEW_RETRY =
            "server/src/main/java/com/intelligentresume/interview/service/InterviewRetryService.java";
    private static final String OPTIMIZE_CONTROLLER =
            "server/src/main/java/com/intelligentresume/ai/optimize/controller/InlineOptimizeController.java";

    @Test
    @DisplayName("通用任务 retry 只重置状态、复用 input_snapshot_json，不组装输入也不要求预览令牌")
    void genericRetryReusesSnapshot() throws Exception {
        String body = methodBody(read(TASK_SERVICE), "public AiTaskStatusResponse retry(");
        assertTrue(body.contains("setStatus("), TASK_SERVICE + " 的 retry 未重置任务状态（实现可能已迁移，更新本门禁锚点）");
        assertFalse(body.contains("setInputSnapshotJson"),
                TASK_SERVICE + " 的 retry 重新写入了 input_snapshot_json —— 一旦重试会重新组装输入，"
                        + "用户看到的内容就可能与上次不同，此时该端点**必须**纳入预览令牌，"
                        + "并同步改 docs/05 的『重试不纳入令牌』表述（D8）");
        assertFalse(body.contains("previewGuard") || body.contains("AiPreviewGuard"),
                TASK_SERVICE + " 的 retry 引用了预览守卫 —— 若改为要求令牌，请同步改决策登记与文档，不要只改代码");
        assertFalse(body.toLowerCase().contains("assembl") || body.contains("Prompt"),
                TASK_SERVICE + " 的 retry 出现输入组装/prompt 构造 —— retry 的前提是『同一份输入原样重跑』（D8）");
    }

    @Test
    @DisplayName("面试 ai/retry 重跑同一次 attempt，不重新出题也不要求预览令牌")
    void interviewRetryReusesAttempt() throws Exception {
        String body = methodBody(read(INTERVIEW_RETRY), "public InterviewStateResponse retryAi(");
        assertTrue(body.contains("latestFailedAttempt"),
                INTERVIEW_RETRY + " 的 retryAi 未复用既有失败 attempt —— 重试应原地重跑，而不是新建一轮");
        assertFalse(body.contains("previewGuard") || body.contains("AiPreviewGuard"),
                INTERVIEW_RETRY + " 的 retryAi 引用了预览守卫 —— 与 D8『重试不纳入令牌』冲突，改前先改决策");
        assertFalse(body.contains("buildPrompt") || body.contains("buildQuestionPrompt"),
                INTERVIEW_RETRY + " 的 retryAi 重新构造了 prompt —— 那样重试产出的题目/评语会变，必须先预览（D8）");
        assertFalse(body.contains("questionText(") || body.contains("setQuestionText"),
                INTERVIEW_RETRY + " 的 retryAi 改写了题目文本 —— 重试必须重跑同一题同一答");
    }

    @Test
    @DisplayName("retry 端点签名不得出现 X-AI-Preview-Token（令牌只加在创建端点）")
    void retryEndpointsCarryNoPreviewToken() throws Exception {
        String source = read(OPTIMIZE_CONTROLLER);
        String method = methodBody(source, "public ResponseEntity<ApiResponse<AiTaskStatusResponse>> retry(");
        assertFalse(method.contains("X-AI-Preview-Token") || method.contains("previewToken"),
                OPTIMIZE_CONTROLLER + " 的 retry 端点开始要求预览令牌 —— 若确实需要，说明它已变成『会重组输入的创建』，"
                        + "须同步更新 D8 决策、docs/05 与前端预览流程");
    }

    // ---- helpers ----

    /** 从签名所在位置截取方法体（按花括号配对，忽略字符串里的括号足够本场景使用）。 */
    private String methodBody(String source, String signature) {
        int start = source.indexOf(signature);
        assertTrue(start >= 0, "找不到方法签名，门禁锚点失效（实现被重命名/移动？）：" + signature);
        int open = source.indexOf('{', start);
        assertTrue(open > start, "签名后找不到方法体起始花括号：" + signature);
        int depth = 0;
        for (int i = open; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return source.substring(open, i + 1);
                }
            }
        }
        throw new AssertionError("方法体未闭合：" + signature);
    }

    private String read(String relative) throws Exception {
        return SourceText.read(repoFile(relative));
    }

    private Path repoFile(String relative) {
        Path serverRoot = Path.of("").toAbsolutePath();
        Path fromServer = serverRoot.resolve("..").resolve(relative).normalize();
        Path target = Files.exists(fromServer) ? fromServer : serverRoot.resolve(relative);
        assertTrue(Files.exists(target), "找不到文件: " + relative + "（门禁必须在仓库内运行）");
        return target;
    }
}
