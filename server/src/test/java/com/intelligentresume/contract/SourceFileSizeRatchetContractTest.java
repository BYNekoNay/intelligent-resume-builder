package com.intelligentresume.contract;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「单文件 300 行」硬门禁的**棘轮**实现（此前仓内没有任何行数判据，超限文件永远不红）。
 *
 * <p>为什么是棘轮而不是一次性清单：本仓现有 15 个生产源文件已超 300 行（最大 694），
 * 一次性要求全部达标会把门禁变成长期红条，于是没人看。棘轮的做法是——
 * **存量登记、只许下调**：
 * <ul>
 *   <li>未登记的新文件一旦超限 → 立即红（不许再欠新债）；</li>
 *   <li>已登记文件行数上涨 → 红（旧债不许涨）；</li>
 *   <li>条目失效（文件已降到 300 行以内、或文件已不存在）→ 红（必须删条目，否则基线留空档，
 *       「拆干净了又长回去」不会被发现）。</li>
 * </ul>
 *
 * <p><b>口径：总物理行数</b>（{@code Files.readAllLines().size()}，空行与注释都算）。
 * 为什么不用「非空行」或「非空非注释」：项目规则的原话是「单文件 ≤ 300 行」，判据必须与规则
 * **字面一致**；按非空行量，会把 694 行的 {@code JavaResumePdfRenderer} 记成 629，
 * 同一事实出现两份口径——本仓已经为此吃过亏。宁可判据比规则更严，也不要口径漂移。
 *
 * <p><b>范围：生产代码 + 测试代码</b>（2026-10-24 起，批次 3）。两侧**上限不同**：
 * <ul>
 *   <li>{@code src/main/java}：**300 行**（原口径不变）；</li>
 *   <li>{@code src/test/java}：**400 行**。理由已由用户拍板：IT 类超长往往是「同一业务链的 N 个场景」，
 *       拆开要复制整份 {@code @SpringBootTest} 装配与 fixture，成本与收益不成比例。
 *       但**必须有上限**：扩根前 18 个 → 扩根前实测已是 **22 个**、最大从 623 涨到 **708 行**，
 *       正是「没门禁盯着就会一直长」的实证。</li>
 * </ul>
 *
 * <p><b>每个根各自有扫描下限</b>（{@link ScanRoot#minFiles()}）：不能用全局单一阈值 ——
 * 两个根合计约 499 个文件，若测试根被改名/移走，总数仍远超 250，测试侧棘轮会**一声不响地关掉**
 * 而断言依旧绿。这正是本文件反复强调的「门禁假绿」。
 *
 * <p><b>门禁必须真的会红</b>：{@link #scanFindsAPlausibleNumberOfFiles()} 兜住「glob 写错 →
 * 一个文件都没扫到 → 三条断言全部空转 → 永远绿」这种最典型的失效（见
 * {@code references/01-standards/test-integrity-anti-gaming.md} 的「门禁假绿」）。
 */
class SourceFileSizeRatchetContractTest {

    /** 一个被扫描的源码根：上限（总物理行数）+ 扫描下限（防该根静默失效）。 */
    private record ScanRoot(String path, int limit, int minFiles) {
    }

    /**
     * 被扫描的源码根（相对 server/）。
     *
     * <p>下限取值：main 当前 331 个（留出拆分类余量取 250）；test 当前 168 个（取 120）。
     * 任一根扫不到下限即红 —— 不能只校验总数。
     */
    private static final List<ScanRoot> SCAN_ROOTS = List.of(
            new ScanRoot("src/main/java", 300, 250),
            new ScanRoot("src/test/java", 400, 120));

    /** 棘轮基线（相对 server/）。删掉这个文件 → 门禁红，不允许「摘掉基线求绿」。 */
    private static final String BASELINE = "src/test/resources/source-size-baseline.txt";

    @Test
    @DisplayName("每个扫描根都必须真的扫到文件：任一根扫空即红（防单根静默失效）")
    void scanFindsAPlausibleNumberOfFiles() throws IOException {
        Map<String, String> problems = new TreeMap<>();
        for (ScanRoot root : SCAN_ROOTS) {
            int scanned = filesIn(root).size();
            if (scanned < root.minFiles()) {
                problems.put(root.path(), "只扫到 " + scanned + " 个 .java 文件（下限 " + root.minFiles()
                        + "）—— 该根的行数门禁等于空转：检查目录是否存在、路径解析是否失效");
            }
        }
        assertTrue(problems.isEmpty(),
                () -> "以下扫描根的扫描数量不足（只校验总数会让某一根静默失效）：\n" + report(problems));
    }

    @Test
    @DisplayName("未登记的生产源文件不得超过 300 行（不许欠新债）")
    void unregisteredFilesMustStayWithinTheLimit() throws IOException {
        Map<String, Integer> baseline = baseline();
        Map<String, String> problems = new TreeMap<>();
        lineCounts().forEach((relative, lines) -> {
            int limit = limitOf(relative);
            if (lines > limit && !baseline.containsKey(relative)) {
                problems.put(relative, "实际 " + lines + " 行 > 允许 " + limit + " 行（" + rootOf(relative).path()
                        + "），且不在 " + BASELINE + " 登记：请拆文件（生产按分层拆、测试拆场景类），不要登记进基线");
            }
        });
        assertTrue(problems.isEmpty(), () -> "以下源文件超过各自上限且未登记：\n" + report(problems));
    }

    @Test
    @DisplayName("已登记文件的行数只允许下调：上涨即红")
    void registeredFilesMustNotGrow() throws IOException {
        Map<String, Integer> baseline = baseline();
        Map<String, Integer> counts = lineCounts();
        Map<String, String> problems = new TreeMap<>();
        for (Map.Entry<String, Integer> entry : baseline.entrySet()) {
            Integer current = counts.get(entry.getKey());
            if (current == null) {
                problems.put(entry.getKey(), "基线登记了但文件不存在（或不再被扫描）：请删掉这条陈旧条目");
                continue;
            }
            if (current > entry.getValue()) {
                problems.put(entry.getKey(), "实际 " + current + " 行 > 允许 " + entry.getValue()
                        + " 行（+" + (current - entry.getValue()) + "）：棘轮只许下调，请把逻辑拆到独立文件，"
                        + "不要改大基线值");
            }
        }
        assertTrue(problems.isEmpty(), () -> "以下已登记文件行数上涨：\n" + report(problems));
    }

    @Test
    @DisplayName("基线条目不得失效：文件降到 300 行以内时必须删条目（否则留空档）")
    void staleBaselineEntriesMustBeRemoved() throws IOException {
        Map<String, Integer> baseline = baseline();
        Map<String, Integer> counts = lineCounts();
        Map<String, String> problems = new TreeMap<>();
        for (Map.Entry<String, Integer> entry : baseline.entrySet()) {
            Integer current = counts.get(entry.getKey());
            if (current != null && current <= limitOf(entry.getKey())) {
                problems.put(entry.getKey(), "实际 " + current + " 行已达标（≤ " + limitOf(entry.getKey())
                        + "）：请从 " + BASELINE + " 删除该行，让棘轮收紧");
            }
        }
        assertTrue(problems.isEmpty(), () -> "以下基线条目已失效：\n" + report(problems));
    }

    @Test
    @DisplayName("基线本身必须可解析：文件存在、每行 path=行数、无重复条目")
    void baselineMustBeWellFormed() throws IOException {
        Map<String, Integer> baseline = baseline();
        assertFalse(baseline.isEmpty(), BASELINE + " 解析出 0 条登记，门禁会退化成「只要不超限就绿」");

        Path path = serverFile(BASELINE);
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        Map<String, Integer> seen = new LinkedHashMap<>();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            assertTrue(line.contains("="),
                    BASELINE + " 第 " + (i + 1) + " 行缺少 '='：" + line);
            String key = line.substring(0, line.lastIndexOf('=')).trim();
            Integer previous = seen.put(key, i + 1);
            assertTrue(previous == null,
                    BASELINE + " 第 " + (i + 1) + " 行重复登记了 " + key + "（首次在第 " + previous + " 行）");
        }
        assertEquals(seen.size(), baseline.size(), "基线行数与解析结果不一致：" + BASELINE + " 可能有一行格式非法");
        assertTrue(baseline.keySet().stream().noneMatch(key -> key.startsWith("/") || key.contains("\\")),
                "基线路径必须是相对 server/ 的正斜杠路径，实际为：" + baseline.keySet());
        assertTrue(baseline.keySet().stream().allMatch(key -> SCAN_ROOTS.stream()
                        .anyMatch(root -> key.startsWith(root.path() + "/"))),
                "基线条目必须落在某个扫描根下（否则它永远不参与判定，等于一条死条目）：" + baseline.keySet());
    }

    // ------------------------------------------------------------------ 内部

    private Map<String, Integer> baseline() throws IOException {
        Path path = serverFile(BASELINE);
        Map<String, Integer> entries = new LinkedHashMap<>();
        for (String raw : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            int split = line.lastIndexOf('=');
            if (split <= 0) {
                continue;   // 格式问题由 baselineMustBeWellFormed 报，这里只跳过
            }
            entries.put(line.substring(0, split).trim(), Integer.parseInt(line.substring(split + 1).trim()));
        }
        return entries;
    }

    /** 扫描到的每个文件的总物理行数（键为相对 server/ 的路径，排序稳定便于对比失败输出）。 */
    private Map<String, Integer> lineCounts() throws IOException {
        Map<String, Integer> counts = new TreeMap<>();
        for (Path file : javaFiles()) {
            counts.put(toRelative(file), totalLines(file));
        }
        return counts;
    }

    /** 全部扫描根下的 .java 文件。 */
    private List<Path> javaFiles() throws IOException {
        List<Path> files = new ArrayList<>();
        for (ScanRoot root : SCAN_ROOTS) {
            files.addAll(filesIn(root));
        }
        return files;
    }

    /** 某一个扫描根下的 .java 文件。 */
    private List<Path> filesIn(ScanRoot root) throws IOException {
        Path base = serverFile(root.path());
        try (Stream<Path> stream = Files.walk(base)) {
            return stream.filter(Files::isRegularFile)
                    .filter(file -> file.getFileName().toString().endsWith(".java"))
                    .map(Path::normalize)
                    .sorted()
                    .toList();
        }
    }

    /** 该路径属于哪个扫描根（也用于把"写错路径的基线条目"暴露出来）。 */
    private ScanRoot rootOf(String relativePath) {
        return SCAN_ROOTS.stream()
                .filter(root -> relativePath.startsWith(root.path() + "/"))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "基线条目不在任何扫描根下（" + relativePath + "）：它永远不会参与判定，请修正路径或删除"));
    }

    private int limitOf(String relativePath) {
        return rootOf(relativePath).limit();
    }

    /** 总物理行数（空行与注释都算 —— 见类注释里的口径理由）。 */
    static int totalLines(Path file) throws IOException {
        return Files.readAllLines(file, StandardCharsets.UTF_8).size();
    }

    private String toRelative(Path file) {
        return serverRoot().relativize(file).toString().replace('\\', '/');
    }

    private String report(Map<String, String> problems) {
        StringBuilder builder = new StringBuilder();
        problems.forEach((key, value) -> builder.append("  ").append(key).append(" —— ").append(value).append('\n'));
        return builder.toString();
    }

    private Path serverFile(String relative) {
        Path target = serverRoot().resolve(relative).normalize();
        assertTrue(Files.exists(target), "找不到 " + relative + "（门禁必须在仓库内运行）");
        return target;
    }

    private boolean existsAnyRoot(Path base) {
        return SCAN_ROOTS.stream().anyMatch(root -> Files.isDirectory(base.resolve(root.path())));
    }

    /** 测试从 server/ 运行；兼容从仓库根运行（此时源码在 server/ 之下）。 */
    private Path serverRoot() {
        Path workingDir = Path.of("").toAbsolutePath();
        if (existsAnyRoot(workingDir)) {
            return workingDir;
        }
        Path nested = workingDir.resolve("server");
        assertTrue(existsAnyRoot(nested), "定位不到任何扫描根：" + workingDir);
        return nested;
    }
}
