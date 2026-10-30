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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 留存期口径一致性门禁（静态）：规范文档里写明的**留存天数**必须与
 * {@code application.yml} 的实际默认值一致；生命周期表里的「硬删」承诺必须按**档位**
 * 标注实现状态（A 档已实施者不得再标计划中；B 档与未覆盖资源必须保留「计划中」）。
 *
 * <p>背景（第四十九批取证）：AI 任务留存在第十三代按用户确认定为「90 天压缩快照」并落地
 * （{@code app.ai.task.retention-days: ${AI_TASK_RETENTION_DAYS:90}}），但 docs/04 §7.1 生命周期
 * 表、docs/07、docs/08 §9.6 三处仍写 **30 天** —— 同一事实在「记忆、决策、执行」三个环节
 * 呈现三种数值。规范的留存期是面向用户与合规的承诺，漂移即虚假承诺。
 *
 * <p>背景（第六十四批）：决策 D2 把「软删 30 天后 7 天内硬删」拆成两档 —— A 档「无引用者」
 * 已对 {@code career_material} / {@code resume_version} 落地清扫作业，B 档「被引用者转最小快照」
 * 与账户侧仍属计划中。故「必须含计划中」的旧断言按档位细分：**A 档不得再被标为计划中，
 * B 档与未覆盖资源必须仍标**。双向断言保证「未实现时不许去掉标注」与「已实现后不许继续标
 * 计划中」两个方向都会被门禁抓到。
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

    /** A/B 两档均已实施的生命周期行（行首标记）。 */
    private static final List<String> IMPLEMENTED_TIER_ROWS = List.of(
            "职业资料", "简历版本", "JD、简历主记录", "投递记录", "面试回答资产");

    /**
     * 硬删/处置承诺未完全实现的生命周期行（行首标记）。
     * B7 后投递记录与面试回答资产已实施；剩余为面试会话与作答记录 —— 它们没有用户级删除入口，
     * 当前仅随账户删除级联硬删，必须保留「计划中」标注防止被读作已兑现。
     */
    private static final List<String> PENDING_ROWS = List.of("面试会话与作答记录");

    /** 表示「还没做完」的措辞。账户行与手册里的账户句都不得在窗口内出现。 */
    private static final List<String> UNFINISHED = List.of("尚未实现", "仍无实现", "仍待实现", "计划中");

    /**
     * 「账户」之后多少字符内视为同一句。
     *
     * <p>必须在**窗口内**判定，不能整行判定：`docs/08` §9.6 有一句同时讲了两个事实 ——
     * 「投递/面试数据的硬删仍**计划中**……账户删除的 7 天撤销窗口……**已实施**」。
     * 整行判「不得含计划中」会把它误判成红（假红），整行判「必须含已实施」又会放过
     * 「账户仍是计划中、投递已实施」的错位（假绿）。
     */
    private static final int ACCOUNT_WINDOW = 80;

    /** 手册中承载「账户删除」状态句的章节（起止标题各一）。 */
    private static final List<DocSection> ACCOUNT_SECTIONS = List.of(
            new DocSection("docs/07-测试计划与验收说明书.md", "### 5.10", "### 5.11"),
            new DocSection("docs/08-部署与运维说明书.md", "### 9.6", "## 10."));

    private record DocSection(String relativePath, String start, String end) {}

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
    @DisplayName("A/B 两档均已实施：对应行必须标「已实施」，且不得再标「计划中」")
    void implementedTiersAreFullyMarkedImplemented() throws Exception {
        String lifecycle = read("docs/04-数据库设计说明书.md");
        assertTrue(!IMPLEMENTED_TIER_ROWS.isEmpty(), "已实施行清单不得为空（否则本门禁空转）");

        for (String marker : IMPLEMENTED_TIER_ROWS) {
            String row = lifecycleRow(lifecycle, marker);
            assertTrue(row.contains("已实施"),
                    "该资源的两档已在决策 D2 阶段 1 / 阶段 2 / 阶段 2b 落地（清扫作业 + 数值配置校验），"
                            + "必须显式标注「已实施」，不得继续被读作未兑现：\n" + row);
            assertFalse(row.contains("计划中"),
                    "该资源的 A 档（无引用者硬删）与 B 档（被引用者转最小快照）均已落地"
                            + "—— 不得再标「计划中」，否则与实现不一致（第六十八批起）：\n" + row);
        }
    }

    @Test
    @DisplayName("未实现的资源（投递与面试数据）仍显式标注「计划中/尚未实现」，不得留作已兑现")
    void pendingRowsStayExplicit() throws Exception {
        String lifecycle = read("docs/04-数据库设计说明书.md");
        assertTrue(!PENDING_ROWS.isEmpty(), "未实现行清单不得为空（否则本门禁空转）");

        for (String marker : PENDING_ROWS) {
            String row = lifecycleRow(lifecycle, marker);
            assertTrue(row.contains("计划中") || row.contains("尚未实现"),
                    "该资源的处置承诺尚无实现（面试会话与作答记录无用户级删除入口，"
                            + "`RetentionPurgeService` 未覆盖），必须显式标注「计划中」或「尚未实现」，"
                            + "否则读者会当作已兑现：\n" + row);
        }
    }

    @Test
    @DisplayName("账户行必须标「已实施」（阶段 3 已于第 74 批落地）")
    void accountRowIsMarkedImplemented() throws Exception {
        String row = lifecycleRow(read("docs/04-数据库设计说明书.md"), "账户");

        assertTrue(row.contains("已实施"),
                "账户侧（7 天撤销窗口 + `AccountPurgeService` 级联清扫）已于第 74 批落地，"
                        + "生命周期表必须标「已实施」，否则读者会当作未兑现：\n" + row);
        for (String unfinished : List.of("尚未实现", "仍无实现", "仍待实现")) {
            assertFalse(row.contains(unfinished),
                    "账户行同时出现「已实施」与「" + unfinished + "」—— 同一行自我矛盾：\n" + row);
        }
    }

    @Test
    @DisplayName("docs/07 与 docs/08 的账户删除措辞与 docs/04 同极性")
    void accountDeletionWordingAgreesAcrossManuals() throws Exception {
        List<String> problems = new ArrayList<>();

        for (DocSection section : ACCOUNT_SECTIONS) {
            String body = sectionOf(read(section.relativePath()), section.start(), section.end());
            List<Integer> anchors = indexesOf(body, "账户");
            assertTrue(!anchors.isEmpty(),
                    section.relativePath() + " 的「" + section.start() + "」节里找不到「账户」二字 —— "
                            + "锚点失效。解析不到时必须失败，不得当成「没有违规」");

            for (int at : anchors) {
                String window = body.substring(at, Math.min(body.length(), at + ACCOUNT_WINDOW));
                for (String unfinished : UNFINISHED) {
                    if (window.contains(unfinished)) {
                        problems.add("  " + section.relativePath() + " 的「账户」句仍写「" + unfinished
                                + "」，与 docs/04 §7.1「已实施」冲突：" + window.replace('\n', ' '));
                    }
                }
            }
        }

        assertTrue(problems.isEmpty(),
                "同一事实在手册间口径不一致（账户侧已实施，只有投递/面试硬删未实现）：\n"
                        + String.join("\n", problems));
    }

    /** 取 docs/04 §7.1 生命周期表中以 {@code marker} 开头的整行。 */
    private String lifecycleRow(String lifecycle, String marker) {
        Matcher row = Pattern.compile("(?m)^\\|\\s*" + Pattern.quote(marker) + "[^\\n]*").matcher(lifecycle);
        assertTrue(row.find(), "docs/04 §7.1 生命周期表应保留以「" + marker + "」开头的行");
        return row.group();
    }

    /** 取以 {@code start} 起、{@code end} 止的章节正文（不含 end 标题）。 */
    private String sectionOf(String text, String start, String end) {
        int from = text.indexOf(start);
        assertTrue(from >= 0, "找不到章节标题「" + start + "」—— 判据锚点失效（不得当成通过）");
        int to = text.indexOf(end, from + start.length());
        assertTrue(to > from, "找不到章节结束标记「" + end + "」—— 判据锚点失效（不得当成通过）");
        return text.substring(from, to);
    }

    /** 返回 {@code needle} 在文本中的全部起始下标。 */
    private List<Integer> indexesOf(String text, String needle) {
        List<Integer> found = new ArrayList<>();
        for (int i = text.indexOf(needle); i >= 0; i = text.indexOf(needle, i + 1)) {
            found.add(i);
        }
        return found;
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
        return SourceText.read(repoFile(relativePath));
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
