package com.intelligentresume.contract;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 悬而未决登记册的**状态机门禁**（静态）：{@code docs/decisions/OPEN-DECISIONS.md} 是项目唯一的
 * 待决项台账，铁律是「只追加 + 就地关闭」。历史上它出过两类失真，本门禁正是针对这两类写的：
 *
 * <ul>
 *   <li><b>部分落地却写着 RESOLVED</b>：2026-10-01 的留存条目曾出现「同一行先说 RESOLVED、
 *       中段又说『故本项不是 RESOLVED』」—— 阶段 3 落地后只往后追加了新段落，没有回头改中段那句。
 *       读者按第一句理解，实际结论相反。根因是「只追加」被误用成「不回头核对」。</li>
 *   <li><b>永久挂着没人管</b>：OPEN 行的 {@code Resolves When} 写成 {@code —} 或空，等于承诺了一个
 *       永远不会到来的触发条件，条目从此没有到期日。</li>
 * </ul>
 *
 * <p>判据刻意做成「封闭集 + 必填字段」而不是「检查某句特定中文」：前者随条目增减自动适用，
 * 后者只能守住一条具体记录，且一改措辞就失效。
 *
 * <p><b>表格解析的两个坑</b>（本文件写作时已实测）：
 * <ol>
 *   <li>单元格正文里可能出现**裸竖线** —— 例如 K7 条目里的 {@code 均 |d|>δ=8}。按单个 {@code |}
 *       切分会把「8 列」读成「10 列」从而**假红**（实测：28 行里有 1 行变成 10 列）。
 *       故按 {@code 空格|空格} 切分；实测该口径下 28 行全部恰好 8 列。</li>
 *   <li>表格前后有标题与引用块（{@code >}），且表头行本身也以 {@code |} 开头。
 *       故数据行以「首格是 ISO 日期」作为唯一判据，表头（首格为 {@code Date}）与分隔行自然排除。</li>
 * </ol>
 *
 * <p>与 {@code RetentionPolicyContractTest} 一样，**解析不到必须失败而不是跳过** ——
 * 一个在某份文件上匹配 0 行却静默通过的 helper，是本仓库已经踩过的假绿形态。
 */
class OpenDecisionRegisterContractTest {

    private static final String REGISTER = "docs/decisions/OPEN-DECISIONS.md";

    private static final Pattern ISO_DATE = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");

    /** 状态取值封闭集。顺序无关（判定用 startsWith，三者互不为前缀）。 */
    private static final List<String> STATUSES = List.of("OPEN", "RESOLVED", "PARTIALLY-RESOLVED");

    /**
     * 已闭环条目的结论标记。允许两种合法形态：{@code Resolution:} / {@code Decision:}，
     * 以及带限定语的 {@code Resolution（部分）:} —— 后者用于区分「只解决了一部分」，
     * 是本册已经采用的写法（初始版本只认裸的 {@code Resolution:}，把这种写法误判成了「没写结论」）。
     */
    private static final Pattern RESOLUTION_MARK =
            Pattern.compile("(Resolution|Decision)\\s*(（[^）]*）)?\\s*[:：]");

    /**
     * 明确表示「还没做完」的措辞。它们出现在标为 RESOLVED 的行里，就说明**部分落地被写成了已闭环** ——
     * 这正是本册历史上那一类失真的原始形态（标了 RESOLVED，正文却说还有东西没实现）。
     * 只收录不会出现在「历史叙述」里的强标记词，避免把「此前未实现」这类回溯说明误判成残留。
     */
    private static final List<String> RESIDUAL_WORDS = List.of("残余", "尚未实现", "仍无实现", "待排期");

    /** 一行的解析结果：行号 + 单元格。 */
    private record Row(int lineNo, List<String> cells) {

        String status() {
            return cells.get(cells.size() - 1);
        }

        String resolvesWhen() {
            return cells.get(cells.size() - 2);
        }

        /** 去掉 Markdown 强调标记后的状态前缀，用于封闭集判定。 */
        String normalizedStatus() {
            return status().replace("*", "").trim();
        }
    }

    @Test
    @DisplayName("每条登记项都是 8 列且首格为 ISO 日期（表格结构被破坏即红）")
    void rowsHaveExpectedShape() throws Exception {
        List<Row> rows = rows();

        // 表格被清空/解析失效时，下面所有逐行断言都会「因为没有行而全部通过」—— 先钉住规模。
        assertTrue(rows.size() >= 20,
                "登记册只解析出 " + rows.size() + " 条数据行（应 ≥20）—— 表格被误改，或解析口径失效。"
                        + "解析不到数据时必须失败，不得当成「没有违规」");

        List<String> problems = new ArrayList<>();
        for (Row row : rows) {
            if (row.cells().size() != 8) {
                problems.add("  第 " + row.lineNo() + " 行解析出 " + row.cells().size()
                        + " 列（应为 8 列：Date/Source/Open Item/Related Constraints/Current Leaning/"
                        + "Blocked By/Resolves When/Status）");
            }
            if (!ISO_DATE.matcher(row.cells().get(0)).matches()) {
                problems.add("  第 " + row.lineNo() + " 行首格不是日期：" + row.cells().get(0));
            }
        }

        assertTrue(problems.isEmpty(),
                "登记册表格结构被破坏（列数漂移会让后续按列取值的判据全部错位）：\n"
                        + String.join("\n", problems));
    }

    @Test
    @DisplayName("状态取值落在封闭集内，且 PARTIALLY-RESOLVED 必须写明残余项")
    void statusVocabularyIsClosed() throws Exception {
        List<String> problems = new ArrayList<>();

        for (Row row : rows()) {
            String status = row.normalizedStatus();
            boolean inSet = STATUSES.stream().anyMatch(status::startsWith);
            if (!inSet) {
                problems.add("  第 " + row.lineNo() + " 行状态不在封闭集 " + STATUSES + " 内："
                        + firstLine(status));
                continue;
            }
            if (status.startsWith("PARTIALLY-RESOLVED") && !row.status().contains("残余")) {
                problems.add("  第 " + row.lineNo() + " 行标为 PARTIALLY-RESOLVED 却未写明「残余」项 —— "
                        + "部分落地写成已闭环，读者会当成全部兑现");
            }
        }

        assertTrue(problems.isEmpty(), "状态取值不符合封闭集约定：\n" + String.join("\n", problems));
    }

    @Test
    @DisplayName("已闭环（RESOLVED / PARTIALLY-RESOLVED）的行必须带 Resolution: 或 Decision:")
    void closedRowsCarryResolution() throws Exception {
        List<String> problems = new ArrayList<>();

        for (Row row : rows()) {
            String status = row.normalizedStatus();
            if (status.startsWith("OPEN")) {
                continue;
            }
            if (!RESOLUTION_MARK.matcher(row.status()).find()) {
                problems.add("  第 " + row.lineNo() + " 行标为已闭环，但 Status 里既无 `Resolution:` 也无 "
                        + "`Decision:` —— 等于「标了已解决却没写怎么解决」，下一个人无法复核结论依据");
            }
        }

        assertTrue(problems.isEmpty(), "已闭环条目缺少结论记录：\n" + String.join("\n", problems));
    }

    @Test
    @DisplayName("标为 RESOLVED 的行不得再含未完成措辞（部分落地不许写成已闭环）")
    void fullyResolvedRowsCarryNoResidualWording() throws Exception {
        List<String> problems = new ArrayList<>();

        for (Row row : rows()) {
            String status = row.normalizedStatus();
            // PARTIALLY-RESOLVED 以 PARTIALLY 开头，天然不属于本断言的范围
            if (!status.startsWith("RESOLVED")) {
                continue;
            }
            for (String word : RESIDUAL_WORDS) {
                if (row.status().contains(word)) {
                    problems.add("  第 " + row.lineNo() + " 行标为 RESOLVED，但 Status 里仍有「" + word
                            + "」—— 部分落地被写成了已闭环，读者只会看到 RESOLVED");
                }
            }
        }

        assertTrue(problems.isEmpty(),
                "存在「已闭环但仍有未完成项」的条目（应改标 PARTIALLY-RESOLVED 并写明残余）：\n"
                        + String.join("\n", problems));
    }

    @Test
    @DisplayName("OPEN 行的 Resolves When 必须可判定（不得为空或 —）")
    void openRowsHaveDecidableTrigger() throws Exception {
        List<String> problems = new ArrayList<>();

        for (Row row : rows()) {
            if (!row.normalizedStatus().startsWith("OPEN")) {
                continue;
            }
            String trigger = row.resolvesWhen().trim();
            if (trigger.length() < 6 || "—".equals(trigger) || "-".equals(trigger)) {
                problems.add("  第 " + row.lineNo() + " 行是 OPEN，但 `Resolves When` 不可判定（\""
                        + trigger + "\"）—— 挂一条永远不会到来的触发条件等于永久搁置");
            }
        }

        assertTrue(problems.isEmpty(), "OPEN 条目缺少可判定的到期条件：\n" + String.join("\n", problems));
    }

    /** 解析全部数据行。判定口径：以 {@code |} 开头结尾、去掉首尾竖线后首格是 ISO 日期。 */
    private List<Row> rows() throws Exception {
        String text = SourceText.read(repoFile(REGISTER));
        List<Row> rows = new ArrayList<>();
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (!line.startsWith("|") || !line.endsWith("|") || line.length() < 2) {
                continue;
            }
            List<String> cells = splitCells(line);
            if (cells.isEmpty() || !ISO_DATE.matcher(cells.get(0)).matches()) {
                continue;
            }
            rows.add(new Row(i + 1, cells));
        }
        assertTrue(!rows.isEmpty(), REGISTER + " 未解析出任何数据行 —— 解析口径失效（不得以空集通过）");
        return rows;
    }

    /** 按 {@code 空格|空格} 切分，避免单元格内的裸竖线（如 {@code |d|>δ=8}）被当成列分隔符。 */
    private List<String> splitCells(String line) {
        String inner = line.substring(1, line.length() - 1);
        List<String> cells = new ArrayList<>();
        for (String part : inner.split(" \\| ", -1)) {
            cells.add(part.trim());
        }
        return cells;
    }

    private String firstLine(String text) {
        int cut = text.indexOf("<br>");
        return cut < 0 ? text : text.substring(0, cut);
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
