package com.intelligentresume.contract;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 观测查询预算门禁（2026-10-29）。
 *
 * <p>背景：{@code AppObservability} 曾为每个 AI 任务类型注册一个 Gauge，回调里直接调
 * {@code countAttemptsByTaskTypeAndCreatedAtAfter} —— 8 个类型就是**每次抓取 8 条聚合查询**，
 * 15~30s 抓取间隔下约 2~4 万次/天。宿主机只有 3.6G 且与另一个项目共用，监控成了负载来源，
 * 而这在指标上看不出来（它只是让 scrape 变慢、故障时放大盲区）。
 *
 * <p>判据：Gauge 回调路径**只允许**分组查询（一次取回全部类型），不允许出现「单类型聚合」。
 * 这是「禁止形态」而非「必须有形态」——将来谁把单类型查询写回回调，门禁就红，而不管他换了什么写法。
 */
class ObservabilityQueryBudgetContractTest {

    private static final String OBSERVABILITY =
            "server/src/main/java/com/intelligentresume/common/observability/AppObservability.java";
    private static final String REPOSITORY =
            "server/src/main/java/com/intelligentresume/ai/task/repository/AiTaskRepository.java";

    /** 单类型聚合：出现即意味着「每类型一次查询」的老形态回来了。 */
    private static final Pattern PER_TYPE_AGGREGATE =
            Pattern.compile("countAttemptsByTaskTypeAndCreatedAtAfter");

    @Test
    @DisplayName("Gauge 回调不得调用单类型聚合查询（那是每次抓取 8 条 count 的老形态）")
    void gaugeCallbacksUseGroupedQueryOnly() throws Exception {
        String source = SourceText.read(repoFile(OBSERVABILITY));
        assertFalse(PER_TYPE_AGGREGATE.matcher(source).find(),
                OBSERVABILITY + " 仍在调用按单类型聚合的 count 查询 —— 每个任务类型都会各发一条，"
                        + "8 个类型 = 每次抓取 8 条聚合。请改用 sumAttemptsGroupedByTaskType（一次取回全部类型）。"
                        + "（若确有必要，请先算清这台 3.6G 共用宿主机能否承受，并同步本门禁）");

        assertTrue(source.contains("sumAttemptsGroupedByTaskType"),
                OBSERVABILITY + " 未使用分组查询 —— 观测侧没有按预期收口");
    }

    @Test
    @DisplayName("分组查询必须存在且保留「重试计次」口径（否则 gauge 与配额判定不同源）")
    void groupedQueryKeepsAttemptSemantics() throws Exception {
        String repository = SourceText.read(repoFile(REPOSITORY));
        Matcher matcher = Pattern.compile(
                "sumAttemptsGroupedByTaskType\\s*\\([^)]*\\)").matcher(repository);
        assertTrue(matcher.find(), REPOSITORY + " 缺少 sumAttemptsGroupedByTaskType —— 解析失效（不得当成通过）");

        // 同文件内两处查询的 CASE 口径必须一致：retryCount < 1 THEN 1 ELSE retryCount
        List<Integer> caseOccurrences = new ArrayList<>();
        Matcher caseMatcher = Pattern.compile("WHEN t\\.retryCount < 1 THEN 1 ELSE t\\.retryCount").matcher(repository);
        while (caseMatcher.find()) {
            caseOccurrences.add(caseMatcher.start());
        }
        assertTrue(caseOccurrences.size() >= 3,
                REPOSITORY + " 里「重试计次」口径的 CASE 只出现 " + caseOccurrences.size()
                        + " 次（期望 ≥3：按用户、按类型、按类型分组三条查询共用同一口径）。"
                        + "少一条意味着某条路径的口径与配额判定不同源，gauge 会与真实消耗量不符");
    }

    private Path repoFile(String relative) {
        Path serverRoot = Path.of("").toAbsolutePath();
        Path fromServer = serverRoot.resolve("..").resolve(relative).normalize();
        Path target = Files.exists(fromServer) ? fromServer : serverRoot.resolve(relative);
        assertTrue(Files.exists(target), "找不到文件: " + relative + "（门禁必须在仓库内运行）");
        return target;
    }
}
