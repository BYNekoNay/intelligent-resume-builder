package com.intelligentresume.contract;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 留存期口径一致性门禁（静态）：规范文档里写明的**留存天数**必须与
 * {@code application.yml} 的实际默认值一致。
 *
 * <p>背景（第四十九批取证）：AI 任务留存在第十三代按用户确认定为「90 天压缩快照」并落地
 * （{@code app.ai.task.retention-days: ${AI_TASK_RETENTION_DAYS:90}}），但 docs/04 §7.1 生命周期
 * 表、docs/07、docs/08 §9.6 三处仍写 **30 天** —— 同一事实在「记忆、决策、执行」三个环节
 * 呈现三种数值（报告把 90 天记为已确认、规范文档写 30 天、实现是 90 天）。规范的留存期是
 * 面向用户与合规的承诺，漂移即虚假承诺，且此前的门禁（{@code ConfigFallbackContractTest}）
 * 只覆盖「代码兜底 vs yml 默认」，不覆盖「文档 vs yml」。
 *
 * <p>比对方式：对每个（文档，正则）对，正则**必须**命中并捕获天数，再与 yml 默认值比对。
 * 刻意要求「必须命中」——文档被改写/删除该句时门禁报红，迫使维护者显式维护该承诺，
 * 而不是让承诺悄悄消失。
 */
class RetentionPolicyContractTest {

    /** 文档中登记 AI 任务留存天数的位置：命中即捕获天数。 */
    private static final List<DocClaim> AI_TASK_RETENTION_CLAIMS = List.of(
            new DocClaim("docs/04-数据库设计说明书.md",
                    Pattern.compile("任务完成\\s*(\\d+)\\s*天后删除非必要内容")),
            new DocClaim("docs/05-接口设计说明书.md",
                    Pattern.compile("`app\\.ai\\.task\\.retention-days`（默认\\s*(\\d+)\\s*天）")),
            new DocClaim("docs/07-测试计划与验收说明书.md",
                    Pattern.compile("AI 任务原始输入和结果\\s*(\\d+)\\s*天后删除非必要内容")),
            new DocClaim("docs/08-部署与运维说明书.md",
                    Pattern.compile("AI 任务原始输入和结果完成\\s*(\\d+)\\s*天后删除非必要内容")));

    private record DocClaim(String relativePath, Pattern pattern) {}

    @Test
    @DisplayName("规范文档声明的 AI 任务留存天数与 application.yml 默认值一致")
    void aiTaskRetentionDaysMatchYamlDefault() throws Exception {
        int yamlDays = aiTaskRetentionDaysFromYaml();

        List<String> problems = new ArrayList<>();
        for (DocClaim claim : AI_TASK_RETENTION_CLAIMS) {
            Matcher matcher = claim.pattern().matcher(read(claim.relativePath()));
            if (!matcher.find()) {
                problems.add("  " + claim.relativePath() + " 未命中留存期声明（正则："
                        + claim.pattern().pattern() + "）——若该承诺已迁移或删除，请同步更新本门禁");
                continue;
            }
            int stated = Integer.parseInt(matcher.group(1));
            if (stated != yamlDays) {
                problems.add("  " + claim.relativePath() + " 声明 " + stated
                        + " 天，而 application.yml 默认 " + yamlDays + " 天");
            }
        }

        assertTrue(yamlDays > 0, "未能从 application.yml 解析出 AI 任务留存天数");
        assertTrue(problems.isEmpty(),
                "留存期在规范文档与实现之间不一致（规范的留存期是面向用户/合规的承诺，漂移即虚假承诺）：\n"
                        + String.join("\n", problems));
    }

    @Test
    @DisplayName("软删资源的「硬删」承诺必须显式标注为未实现或已实现，不得留作已兑现")
    void softDeleteHardPurgeClaimIsExplicit() throws Exception {
        // docs/04 §7.1 生命周期表声称「职业资料、JD、简历主记录 / 投递与面试数据」软删恢复期
        // 30 天后「恢复期结束后 7 天内」硬删，但全仓无任何硬删实现（无清扫作业、无 repository
        // 方法、无配置；@Scheduled 只有 AI 留存 / PDF 过期 / 导出过期三处）。该承诺必须带
        // 「计划中」标记，避免被读成已兑现；本断言在实现落地时由维护者改标记，门禁随之报红提醒。
        String lifecycle = read("docs/04-数据库设计说明书.md");
        Matcher row = Pattern.compile("\\|\\s*职业资料、JD、简历主记录\\s*\\|[^\\n]*").matcher(lifecycle);
        assertTrue(row.find(), "docs/04 §7.1 生命周期表应保留「职业资料、JD、简历主记录」行");
        String line = row.group();
        assertTrue(line.contains("计划中") || line.contains("未实现"),
                "该行的硬删承诺尚无实现，必须显式标注「计划中」或「未实现」，否则读者会当作已兑现：\n" + line);
    }

    /** 从 application.yml 取 {@code app.ai.task.retention-days} 的默认值（支持 {@code ${ENV:default}}）。 */
    private int aiTaskRetentionDaysFromYaml() throws Exception {
        try (InputStream input = Files.newInputStream(repoFile("server/src/main/resources/application.yml"))) {
            Object loaded = new Yaml().load(input);
            assertTrue(loaded instanceof Map, "application.yml 顶层应为映射");
            Object raw = resolve((Map<?, ?>) loaded, "app.ai.task.retention-days");
            assertTrue(raw != null, "application.yml 应声明 app.ai.task.retention-days");
            Matcher placeholder = Pattern.compile("^\\$\\{[^:}]+:(\\d+)\\}$").matcher(String.valueOf(raw).trim());
            String value = placeholder.matches() ? placeholder.group(1) : String.valueOf(raw).trim();
            assertEquals(value, String.valueOf(Integer.parseInt(value)), "留存天数应为整数");
            return Integer.parseInt(value);
        }
    }

    private Object resolve(Map<?, ?> root, String dottedKey) {
        Object current = root;
        for (String segment : dottedKey.split("\\.")) {
            if (!(current instanceof Map)) return null;
            current = ((Map<?, ?>) current).get(segment);
            if (current == null) return null;
        }
        return current;
    }

    private String read(String relativePath) throws Exception {
        return Files.readString(repoFile(relativePath), StandardCharsets.UTF_8);
    }

    /** 测试从 server/ 运行，资源位于仓库根；兼容从仓库根目录运行。 */
    private Path repoFile(String relative) {
        Path serverRoot = Path.of("").toAbsolutePath();
        Path fromServer = serverRoot.resolve("..").resolve(relative).normalize();
        Path target = Files.exists(fromServer) ? fromServer : serverRoot.resolve(relative);
        assertTrue(Files.exists(target), "找不到文件: " + relative + "（门禁必须在仓库内运行）");
        return target;
    }
}