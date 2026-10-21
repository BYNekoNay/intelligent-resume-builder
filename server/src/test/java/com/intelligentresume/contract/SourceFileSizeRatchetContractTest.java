package com.intelligentresume.contract;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
 * <p><b>范围：{@code src/main/java}</b>。本门禁只管生产代码；测试类是否纳入是独立决策，
 * 纳入时把 {@link #SCAN_ROOT} 扩到 {@code src/test/java} 并在基线里登记即可。
 *
 * <p><b>门禁必须真的会红</b>：{@link #scanFindsAPlausibleNumberOfFiles()} 兜住「glob 写错 →
 * 一个文件都没扫到 → 三条断言全部空转 → 永远绿」这种最典型的失效（见
 * {@code references/01-standards/test-integrity-anti-gaming.md} 的「门禁假绿」）。
 */
class SourceFileSizeRatchetContractTest {

    /** 行数上限（总物理行数）。 */
    private static final int LIMIT = 300;

    /** 被扫描的源码根（相对 server/）。 */
    private static final String SCAN_ROOT = "src/main/java";

    /** 棘轮基线（相对 server/）。删掉这个文件 → 门禁红，不允许「摘掉基线求绿」。 */
    private static final String BASELINE = "src/test/resources/source-size-baseline.txt";

    /** 扫描下限：低于此数说明 glob/路径解析坏了，门禁会假绿。当前 322 个，留出拆分类的余量。 */
    private static final int MIN_EXPECTED_FILES = 250;

    @Test
    @DisplayName("扫描必须真的扫到源码：一个文件都没扫到时门禁必须红（防 glob 假绿）")
    void scanFindsAPlausibleNumberOfFiles() throws IOException {
        int scanned = javaFiles().size();
        assertTrue(scanned >= MIN_EXPECTED_FILES,
                "只扫到 " + scanned + " 个 .java 文件（下限 " + MIN_EXPECTED_FILES + "），"
                        + "行数门禁等于空转：检查 " + SCAN_ROOT + " 是否存在、路径解析是否失效");
    }

    @Test
    @DisplayName("未登记的生产源文件不得超过 300 行（不许欠新债）")
    void unregisteredFilesMustStayWithinTheLimit() throws IOException {
        Map<String, Integer> baseline = baseline();
        Map<String, String> problems = new TreeMap<>();
        lineCounts().forEach((relative, lines) -> {
            if (lines > LIMIT && !baseline.containsKey(relative)) {
                problems.put(relative, "实际 " + lines + " 行 > 允许 " + LIMIT + " 行，且不在 " + BASELINE
                        + " 登记：请按子功能拆文件（controller/service/repository 分层），不要把它登记进基线");
            }
        });
        assertTrue(problems.isEmpty(), () -> "以下生产源文件超过 " + LIMIT + " 行且未登记：\n" + report(problems));
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
            if (current != null && current <= LIMIT) {
                problems.put(entry.getKey(), "实际 " + current + " 行已达标（≤ " + LIMIT
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

    /** {@code src/main/java} 下的全部 .java 文件。 */
    private List<Path> javaFiles() throws IOException {
        Path root = serverFile(SCAN_ROOT);
        try (Stream<Path> stream = Files.walk(root)) {
            return stream.filter(Files::isRegularFile)
                    .filter(file -> file.getFileName().toString().endsWith(".java"))
                    .map(Path::normalize)
                    .sorted()
                    .toList();
        }
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

    /** 测试从 server/ 运行；兼容从仓库根运行（此时源码在 server/ 之下）。 */
    private Path serverRoot() {
        Path workingDir = Path.of("").toAbsolutePath();
        if (Files.isDirectory(workingDir.resolve(SCAN_ROOT))) {
            return workingDir;
        }
        Path nested = workingDir.resolve("server");
        assertTrue(Files.isDirectory(nested.resolve(SCAN_ROOT)),
                "定位不到 " + SCAN_ROOT + "：当前工作目录 " + workingDir);
        return nested;
    }
}
